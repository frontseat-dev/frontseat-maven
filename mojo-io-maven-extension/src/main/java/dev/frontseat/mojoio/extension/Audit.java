package dev.frontseat.mojoio.extension;

import dev.frontseat.mojoio.cache.AntPattern;
import dev.frontseat.mojoio.cache.FileTrees;
import dev.frontseat.mojoio.extension.descriptor.ResolvedInput;
import dev.frontseat.mojoio.extension.descriptor.ResolvedOutput;
import dev.frontseat.mojoio.spec.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.inject.Named;
import javax.inject.Singleton;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import jdk.jfr.consumer.RecordingStream;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Audit mode ({@code -Dmojoio.audit=warn|fail}): observes the files every mojo reads and writes and the
 * processes it starts, and reports what its declaration does not cover.
 *
 * <ul>
 *   <li>Maven's JVM: Java Flight Recorder's file and process events, attributed to the mojo that ran
 *       at the time (in a parallel build, to the mojo of the thread).</li>
 *   <li>Test JVMs forked through an {@code argLine} parameter (Surefire, Failsafe): they record too,
 *       into files read when the mojo ends.</li>
 *   <li>Any other process a mojo starts can't be observed: it is reported.</li>
 * </ul>
 *
 * Not seen: existence checks and directory listings (no file is opened), and files read through native
 * code. Every mojo runs (the cache is off), so all of them are audited.
 */
@Named
@Singleton
public class Audit {

    private static final Logger LOG = LoggerFactory.getLogger(Audit.class);
    static final String REPORT = "mojo-io-audit.json";
    private static final String FORK_OPTION = "-XX:StartFlightRecording";
    /**
     * Maven's own files in the build directory, derived from the project's coordinates (part of every
     * key): Maven Archiver reads back the pom.properties it wrote before.
     */
    static final List<String> MAVEN_BOOKKEEPING = List.of("maven-archiver/**", "consumer-*.pom");

    public enum Mode { OFF, WARN, FAIL }

    public static Mode mode(MavenSession session) {
        String v = session.getUserProperties().getProperty("mojoio.audit",
                session.getSystemProperties().getProperty("mojoio.audit", "off"));
        v = v.isBlank() || v.equals("true") ? "warn" : v;
        return Mode.valueOf(v.trim().toUpperCase(Locale.ROOT));
    }

    /** One observed access. {@code kind}: r (read), w (write), p (process start). */
    record Access(Instant time, long thread, char kind, String target, List<String> mavenStack) {

        Access(Instant time, long thread, char kind, String target) {
            this(time, thread, kind, target, null);
        }

        /** Done by Maven or mojo-io, not by the mojo: no frame of the mojo's package on the stack. */
        boolean byMaven(String mojoPackage) {
            return mavenStack != null && (mojoPackage == null || mavenStack.stream().noneMatch(t -> t.startsWith(mojoPackage)));
        }
    }

    /** Code that reads files for Maven itself while a mojo runs: building models, resolving, mojo-io. */
    private static final List<String> MAVEN_CODE = List.of("org.apache.maven.impl.model.",
            "org.apache.maven.internal.impl.model.", "org.apache.maven.model.", "org.apache.maven.internal.transformation.",
            "org.apache.maven.ReactorReader", "org.apache.maven.project.", "org.eclipse.aether.",
            "org.apache.maven.repository.internal.", "dev.frontseat.mojoio.");

    /** What an execution declared, resolved when it started. */
    record Coverage(List<ResolvedInput> inputs, List<ResolvedOutput> outputs, List<String> ignoredWrites,
                    String declaredBy, boolean cacheable, String unresolved) {

        static Coverage undeclared() {
            return new Coverage(List.of(), List.of(), List.of(), null, false, null);
        }
    }

    /** An execution's run. */
    static final class Window {
        final String label;
        final String project;
        final long thread;
        final Path basedir;
        final Path buildDirectory;
        final Coverage coverage;
        /** The package of the mojo's class: frames from it make an access the mojo's. */
        String mojoPackage;
        final Instant start = Instant.now();
        volatile Instant end;
        Path forkDirectory;
        MojoExecution instrumented;
        Xpp3Dom originalConfiguration;
        final List<Access> accesses = Collections.synchronizedList(new ArrayList<>());

        Window(String label, MavenProject project, Coverage coverage) { // package-private for tests
            this.label = label;
            this.project = project.getGroupId() + ":" + project.getArtifactId();
            this.thread = Thread.currentThread().getId();
            this.basedir = project.getBasedir().toPath().toAbsolutePath().normalize();
            this.buildDirectory = Path.of(project.getBuild().getDirectory()).toAbsolutePath().normalize();
            this.coverage = coverage;
        }
    }

    private static final class Run {
        final RecordingStream stream;
        final ConcurrentLinkedQueue<Access> events = new ConcurrentLinkedQueue<>();
        final List<Window> windows = Collections.synchronizedList(new ArrayList<>());
        final boolean parallel;
        final Path directory;
        final Path settings;
        final List<Path> ignoredRoots;
        int forks;

        Run(MavenSession session) {
            parallel = session.isParallel();
            Path root = root(session);
            directory = root.resolve("target").resolve("mojo-io-audit");
            settings = directory.resolve("file-events.jfc");
            ignoredRoots = ignoredRoots(session);
            stream = new RecordingStream();
            for (String event : List.of("jdk.FileRead", "jdk.FileWrite", "jdk.ProcessStart")) {
                // Stacks tell Maven's own reads (models, the resolver) from the mojo's.
                stream.enable(event).withThreshold(Duration.ZERO).withStackTrace();
            }
            stream.onEvent(e -> {
                Access a = access(e);
                if (a != null) {
                    events.add(a);
                }
            });
            stream.startAsync();
        }
    }

    private final Map<MavenSession, Run> runs = Collections.synchronizedMap(new WeakHashMap<>());

    private Run run(MavenSession session) {
        return runs.computeIfAbsent(session, Run::new);
    }

    /** Starts observing an execution, right before it runs. */
    Window begin(MavenSession session, MavenProject project, MojoExecution m, String label, Coverage coverage) {
        Run run = run(session);
        Window w = new Window(label, project, coverage);
        String implementation = m.getMojoDescriptor() == null ? null : m.getMojoDescriptor().getImplementation();
        if (implementation != null && implementation.lastIndexOf('.') > 0) {
            w.mojoPackage = implementation.substring(0, implementation.lastIndexOf('.') + 1);
        }
        if (!Boolean.parseBoolean(session.getUserProperties().getProperty("mojoio.audit.forks", "true"))) {
            run.windows.add(w);
            return w;
        }
        if (m.getMojoDescriptor() != null && m.getMojoDescriptor().getParameterMap().containsKey("argLine")) {
            synchronized (run) {
                w.forkDirectory = run.directory.resolve("forks").resolve(String.valueOf(++run.forks));
            }
            instrumentForks(m, project, session, w, run.settings);
        }
        run.windows.add(w);
        return w;
    }

    /** Stops observing an execution, right after it ran (or failed). */
    void end(Window w) {
        w.end = Instant.now();
        if (w.instrumented != null) {
            w.instrumented.setConfiguration(w.originalConfiguration);
        }
        if (w.forkDirectory != null && Files.isDirectory(w.forkDirectory)) {
            try (var files = Files.list(w.forkDirectory)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".jfr")).toList()) {
                    for (RecordedEvent e : RecordingFile.readAllEvents(f)) {
                        Access a = access(e);
                        if (a != null) {
                            w.accesses.add(a);
                        }
                    }
                }
            } catch (IOException e) {
                LOG.warn("[mojo-io] AUDIT cannot read the recordings of {}'s forked JVMs: {}", w.label, e.toString());
            }
        }
    }

    /**
     * Test JVMs started through an {@code argLine} record their file events too. The option is added to
     * this execution's configuration for this run only.
     */
    private static void instrumentForks(MojoExecution m, MavenProject project, MavenSession session, Window w,
                                        Path settings) {
        try {
            // Written for each fork: a clean of the root project may have deleted the directory.
            Files.createDirectories(w.forkDirectory);
            Files.writeString(settings, FORK_SETTINGS, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String option = FORK_OPTION + ":settings=" + settings + ",filename=" + w.forkDirectory.resolve("fork-%p.jfr")
                + ",dumponexit=true";
        Xpp3Dom original = m.getConfiguration();
        Xpp3Dom configuration = original == null ? new Xpp3Dom("configuration") : new Xpp3Dom(original);
        Xpp3Dom argLine = configuration.getChild("argLine");
        String value;
        if (argLine != null && argLine.getValue() != null) {
            value = argLine.getValue();
        } else {
            value = m.getMojoDescriptor().getParameterMap().get("argLine").getExpression();
            if (argLine == null) {
                argLine = new Xpp3Dom("argLine");
                configuration.addChild(argLine);
            }
        }
        if (value != null && value.contains("${argLine}") && project.getProperties().getProperty("argLine") == null
                && session.getUserProperties().getProperty("argLine") == null
                && session.getSystemProperties().getProperty("argLine") == null) {
            value = value.replace("${argLine}", ""); // unset: Maven would leave the text as is
        }
        argLine.setValue(((value == null ? "" : value) + " " + option).trim());
        w.instrumented = m;
        w.originalConfiguration = original;
        m.setConfiguration(configuration);
    }

    private static Access access(RecordedEvent e) {
        String type = e.getEventType().getName();
        long thread = e.getThread() == null ? -1 : e.getThread().getJavaThreadId();
        List<String> mavenStack = null;
        if (e.getStackTrace() != null) {
            List<String> types = new ArrayList<>();
            boolean maven = false;
            for (var f : e.getStackTrace().getFrames()) {
                String t = f.getMethod().getType().getName();
                types.add(t);
                maven |= MAVEN_CODE.stream().anyMatch(t::startsWith);
            }
            mavenStack = maven ? types : null;
        }
        return switch (type) {
            case "jdk.FileRead" -> e.getString("path") == null ? null : new Access(e.getStartTime(), thread, 'r', e.getString("path"), mavenStack);
            case "jdk.FileWrite" -> e.getString("path") == null ? null : new Access(e.getStartTime(), thread, 'w', e.getString("path"), mavenStack);
            case "jdk.ProcessStart" -> new Access(e.getStartTime(), thread, 'p', e.getString("command"));
            default -> null;
        };
    }

    /** What one execution did that its declaration does not cover. */
    record Finding(String label, String project, String declaredBy, boolean cacheable, List<String> undeclaredReads,
                   List<String> undeclaredWrites, List<String> unobservedProcesses, List<String> reads,
                   List<String> writes) {

        boolean problem() {
            return cacheable && (!undeclaredReads.isEmpty() || !undeclaredWrites.isEmpty() || !unobservedProcesses.isEmpty());
        }
    }

    /**
     * Ends the audit of a build: attributes the events, reports, and in {@code fail} mode returns a
     * failure message when a cacheable execution read, wrote or started something undeclared.
     */
    String finish(MavenSession session) {
        Run run = runs.remove(session);
        if (run == null) {
            return null;
        }
        stop(run.stream);
        List<Window> windows = new ArrayList<>(run.windows);
        for (Access a : run.events) {
            for (Window w : windows) {
                Instant end = w.end == null ? Instant.MAX : w.end;
                boolean inTime = !a.time().isBefore(w.start) && !a.time().isAfter(end);
                if (inTime && (!run.parallel || a.thread() == w.thread)) {
                    w.accesses.add(a);
                    break;
                }
            }
        }
        List<Finding> findings = new ArrayList<>();
        for (Window w : windows) {
            findings.add(analyze(w, run.ignoredRoots, root(session)));
        }
        return report(session, run, findings);
    }

    static Finding analyze(Window w, List<Path> ignoredRoots, Path workspace) {
        Set<Path> reads = new TreeSet<>();
        Set<Path> writes = new TreeSet<>();
        Set<String> processes = new TreeSet<>();
        synchronized (w.accesses) {
            for (Access a : w.accesses) {
                if (a.byMaven(w.mojoPackage)) {
                    continue;
                }
                if (a.kind() == 'p') {
                    if (!a.target().contains(FORK_OPTION)) {
                        processes.add(a.target());
                    }
                    continue;
                }
                Path p = w.basedir.resolve(a.target()).toAbsolutePath().normalize();
                if (ignored(p, ignoredRoots, workspace, w.buildDirectory)) {
                    continue;
                }
                (a.kind() == 'r' ? reads : writes).add(p);
            }
        }
        reads.removeAll(writes); // read back what it wrote itself
        writes.removeIf(p -> !Files.exists(p)); // temporary: deleted again (an archiver's work file)
        List<String> bookkeeping = new ArrayList<>(MAVEN_BOOKKEEPING);
        bookkeeping.addAll(w.coverage.ignoredWrites());
        List<String> undeclaredReads = new ArrayList<>();
        for (Path p : reads) {
            boolean ownBookkeeping = p.startsWith(w.buildDirectory)
                    && AntPattern.selected(FileTrees.relative(w.buildDirectory, p), bookkeeping, List.of());
            if (!ownBookkeeping && !readCovered(p, w.coverage)) {
                undeclaredReads.add(display(w, p));
            }
        }
        List<String> undeclaredWrites = WriteSnapshot.undeclared(writes, w.coverage.outputs(), w.buildDirectory,
                w.coverage.ignoredWrites(), w.basedir).stream().map(r -> display(w, w.basedir.resolve(r).normalize())).toList();
        return new Finding(w.label, w.project, w.coverage.declaredBy(), w.coverage.cacheable(), undeclaredReads,
                undeclaredWrites, new ArrayList<>(processes), reads.stream().map(p -> display(w, p)).toList(),
                writes.stream().map(p -> display(w, p)).toList());
    }

    static boolean readCovered(Path p, Coverage coverage) {
        for (ResolvedOutput o : coverage.outputs()) {
            if (o.covers(p)) {
                return true; // its own earlier output (an incremental tool reading what it wrote last time)
            }
        }
        for (ResolvedInput in : coverage.inputs()) {
            for (Path root : in.paths()) {
                Path r = root.toAbsolutePath().normalize();
                if (p.equals(r)) {
                    return true;
                }
                if (!p.startsWith(r)) {
                    continue;
                }
                if (in.kind() == ResolvedInput.Kind.CLASSPATH
                        || AntPattern.selected(FileTrees.relative(r, p), in.includes(), in.excludes())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The JDK, Maven, the local repository, temporary files, system files and mojo-io's own files. */
    static boolean ignored(Path p, List<Path> roots, Path workspace, Path buildDirectory) {
        for (Path r : roots) {
            // A workspace inside an ignored directory (the temporary directory) is still audited.
            if (p.startsWith(r) && (!p.startsWith(workspace) || r.startsWith(workspace))) {
                return true;
            }
        }
        return p.startsWith(buildDirectory.resolve("mojo-io")) || p.getFileName() == null;
    }

    private static List<Path> ignoredRoots(MavenSession session) {
        List<String> roots = new ArrayList<>(List.of("/proc", "/sys", "/dev", "/etc", "/usr/share", "/usr/lib/locale"));
        roots.add(System.getProperty("java.home"));
        roots.add(System.getProperty("java.io.tmpdir"));
        roots.add(System.getProperty("maven.home"));
        roots.add(System.getProperty("mvnd.home"));
        roots.add(Path.of(System.getProperty("user.home"), ".m2").toString());
        roots.add(Path.of(System.getProperty("user.home"), ".mvnd").toString());
        if (session.getLocalRepository() != null) {
            roots.add(session.getLocalRepository().getBasedir());
        }
        roots.add(MojoIoConfig.from(session).cacheDir().toString());
        roots.add(root(session).resolve("target").resolve("mojo-io-audit").toString());
        roots.add(root(session).resolve(".mvn").resolve("wrapper").toString());
        List<Path> result = new ArrayList<>();
        for (String r : roots) {
            if (r == null || r.isBlank()) {
                continue;
            }
            Path p = Path.of(r).toAbsolutePath().normalize();
            List<Path> forms = new ArrayList<>(List.of(p));
            try {
                forms.add(p.toRealPath());
            } catch (IOException e) {
                // does not exist
            }
            result.addAll(forms);
        }
        return result;
    }

    private static String display(Window w, Path p) {
        return p.startsWith(w.basedir) ? FileTrees.relative(w.basedir, p) : p.toString();
    }

    private String report(MavenSession session, Run run, List<Finding> findings) {
        List<Object> json = new ArrayList<>();
        int problems = 0;
        for (Finding f : findings) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("execution", f.label());
            o.put("project", f.project());
            o.put("declaredBy", f.declaredBy());
            o.put("cacheable", f.cacheable());
            o.put("reads", f.reads());
            o.put("writes", f.writes());
            o.put("undeclaredReads", f.undeclaredReads());
            o.put("undeclaredWrites", f.undeclaredWrites());
            o.put("unobservedProcesses", f.unobservedProcesses());
            json.add(o);
            if (f.problem()) {
                problems++;
                LOG.warn("[mojo-io] AUDIT {} ({}):{}{}{}", f.label(), f.declaredBy(),
                        f.undeclaredReads().isEmpty() ? "" : " reads undeclared " + summary(f.undeclaredReads()) + ";",
                        f.undeclaredWrites().isEmpty() ? "" : " writes undeclared " + summary(f.undeclaredWrites()) + ";",
                        f.unobservedProcesses().isEmpty() ? "" : " starts processes it can't observe "
                                + summary(f.unobservedProcesses()) + ";");
            } else if (f.declaredBy() == null && f.reads().size() + f.writes().size() > 0) {
                LOG.info("[mojo-io] AUDIT {} (no declaration): reads {} file(s), writes {}{}", f.label(),
                        f.undeclaredReads().size(), f.undeclaredWrites().size(),
                        f.unobservedProcesses().isEmpty() ? "" : ", starts " + summary(f.unobservedProcesses()));
            }
        }
        Path file = run.directory.resolve(REPORT);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("format", "mojo-io-audit");
        doc.put("version", 1);
        doc.put("parallel", run.parallel);
        doc.put("executions", json);
        try {
            Files.createDirectories(run.directory);
            Files.writeString(file, Json.write(doc), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("[mojo-io] AUDIT cannot write {}: {}", file, e.toString());
        }
        String parallelNote = run.parallel ? " Parallel build: files read by helper threads of a mojo can't be"
                + " attributed; audit with -T1 to see them." : "";
        LOG.info("[mojo-io] AUDIT {} execution(s) observed, {} with undeclared files or processes; report: {}.{}",
                findings.size(), problems, file, parallelNote);
        if (problems > 0 && mode(session) == Mode.FAIL) {
            return problems + " cacheable execution(s) read, write or start something their declaration does not"
                    + " cover (see the AUDIT lines and " + file + ")";
        }
        return null;
    }

    private static String summary(List<String> items) {
        return items.size() > 8 ? items.subList(0, 8) + " and " + (items.size() - 8) + " more" : items.toString();
    }

    /** Stops the stream once every event recorded so far was delivered (RecordingStream#stop, Java 20+). */
    private static void stop(RecordingStream stream) {
        try {
            RecordingStream.class.getMethod("stop").invoke(stream);
        } catch (ReflectiveOperationException e) {
            try {
                Thread.sleep(2000); // Java 17-19: the stream flushes about once a second
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        } finally {
            stream.close();
        }
    }

    private static Path root(MavenSession session) {
        if (session.getRequest().getMultiModuleProjectDirectory() != null) {
            return session.getRequest().getMultiModuleProjectDirectory().toPath().toAbsolutePath().normalize();
        }
        return Path.of(session.getRequest().getBaseDirectory()).toAbsolutePath().normalize();
    }

    private static final String FORK_SETTINGS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <configuration version="2.0" label="mojo-io audit" description="File and process events of a forked JVM">
              <event name="jdk.FileRead"><setting name="enabled">true</setting><setting name="stackTrace">false</setting><setting name="threshold">0 ms</setting></event>
              <event name="jdk.FileWrite"><setting name="enabled">true</setting><setting name="stackTrace">false</setting><setting name="threshold">0 ms</setting></event>
              <event name="jdk.ProcessStart"><setting name="enabled">true</setting><setting name="stackTrace">false</setting></event>
            </configuration>
            """;
}
