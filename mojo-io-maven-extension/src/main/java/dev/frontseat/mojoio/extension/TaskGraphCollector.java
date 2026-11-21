package dev.frontseat.mojoio.extension;

import dev.frontseat.mojoio.extension.descriptor.DescriptorRegistry;
import dev.frontseat.mojoio.extension.descriptor.InputSpec;
import dev.frontseat.mojoio.extension.descriptor.MojoCacheDescriptor;
import dev.frontseat.mojoio.extension.descriptor.OutputSpec;
import dev.frontseat.mojoio.extension.descriptor.ResolvedInput;
import dev.frontseat.mojoio.extension.descriptor.ResolvedOutput;
import dev.frontseat.mojoio.spec.Json;
import dev.frontseat.mojoio.spec.TaskGraphFormat;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the tool-agnostic task graph ({@value TaskGraphFormat#FORMAT}) in a dry run: each project's
 * mojo executions are described (declared inputs/outputs, cacheability, state file) but not run, and
 * no dependencies are resolved. Written once at the end of the session.
 */
@Named
@Singleton
public class TaskGraphCollector {

    private static final Logger LOG = LoggerFactory.getLogger(TaskGraphCollector.class);

    private final DescriptorRegistry registry;
    private final RuntimeInformation runtime;
    private final DependencyResolution dependencies;
    private final Map<MavenSession, Map<String, Object>> graphs = new java.util.WeakHashMap<>();

    @Inject
    public TaskGraphCollector(DescriptorRegistry registry, RuntimeInformation runtime,
                              DependencyResolution dependencies) {
        this.registry = registry;
        this.runtime = runtime;
        this.dependencies = dependencies;
    }

    synchronized void add(MavenSession session, MavenProject project, List<MojoExecution> executions) {
        Map<String, Object> graph = graphs.computeIfAbsent(session, s -> newGraph(s));
        PathNormalizer normalizer = MojoIoExecutionStrategy.normalizer(session, project);
        Path root = root(session);

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", project.getGroupId() + ":" + project.getArtifactId());
        p.put("version", project.getVersion());
        String rel = root.relativize(project.getBasedir().toPath().toAbsolutePath().normalize()).toString().replace('\\', '/');
        p.put("path", rel);
        p.put("packaging", project.getPackaging());
        List<String> dependsOn = new ArrayList<>();
        for (MavenProject upstream : session.getProjectDependencyGraph().getUpstreamProjects(project, false)) {
            dependsOn.add(upstream.getGroupId() + ":" + upstream.getArtifactId());
        }
        p.put("dependsOn", dependsOn);
        List<String> poms = new ArrayList<>();
        for (MavenProject cur = project; cur != null && cur.getFile() != null; cur = cur.getParent()) {
            Path pom = cur.getFile().toPath().toAbsolutePath().normalize();
            if (!pom.startsWith(root)) {
                break;
            }
            poms.add(path(normalizer, pom));
        }
        p.put("poms", poms);
        p.put("externalDependencies", dependencies.externalDependencies(project, session));

        // A phase and its hooks (Maven 4 before:/after:; Maven 3's generate-/process-/pre-/post- phases are
        // aliases of those hooks) share process-local state, so they must run in one Maven invocation.
        // Computed on the Maven 4 form for both versions, so the graph is the same on both.
        Map<String, Integer> members = new java.util.HashMap<>();
        for (MojoExecution m : executions) {
            if (m.getLifecyclePhase() != null) {
                members.merge(hookBase(hookForm(m.getLifecyclePhase())), 1, Integer::sum);
            }
        }
        List<Object> list = new ArrayList<>();
        for (MojoExecution m : executions) {
            Map<String, Object> e = execution(session, project, m, normalizer);
            String phase = m.getLifecyclePhase();
            String base = phase == null ? null : hookBase(hookForm(phase));
            e.put("canonicalPhase", phase == null ? null : canonicalPhase(phase));
            e.put("runTogether", base != null && members.get(base) > 1
                    && (!hookForm(phase).equals(base) || hasHookMember(executions, base)) ? base : null);
            list.add(e);
        }
        p.put("executions", list);
        @SuppressWarnings("unchecked")
        List<Object> projects = (List<Object>) graph.get("projects");
        projects.add(p);
        LOG.info("[mojo-io] GRAPH {}: {} execution(s) described, none run", project.getArtifactId(), list.size());
    }

    /** Maven 3 phase names that Maven 4 defines as hooks of another phase (Maven 4 form -> Maven 3 name). */
    static final Map<String, String> MAVEN3_ALIASES = Map.ofEntries(
            Map.entry("before:clean", "pre-clean"), Map.entry("after:clean", "post-clean"),
            Map.entry("before:sources", "generate-sources"), Map.entry("after:sources", "process-sources"),
            Map.entry("before:resources", "generate-resources"), Map.entry("after:resources", "process-resources"),
            Map.entry("after:compile", "process-classes"),
            Map.entry("before:test-sources", "generate-test-sources"),
            Map.entry("after:test-sources", "process-test-sources"),
            Map.entry("before:test-resources", "generate-test-resources"),
            Map.entry("after:test-resources", "process-test-resources"),
            Map.entry("after:test-compile", "process-test-classes"),
            Map.entry("before:package", "prepare-package"),
            Map.entry("before:integration-test", "pre-integration-test"),
            Map.entry("after:integration-test", "post-integration-test"),
            Map.entry("before:site", "pre-site"), Map.entry("after:site", "post-site"));

    /** Maven 4 hook form of a phase: process-resources -> after:resources; at:X -> X. */
    static String hookForm(String phase) {
        if (phase.startsWith("at:")) {
            return phase.substring(3);
        }
        for (Map.Entry<String, String> e : MAVEN3_ALIASES.entrySet()) {
            if (e.getValue().equals(phase)) {
                return e.getKey();
            }
        }
        return phase;
    }

    /** Maven 3 name of a phase where one exists (after:resources -> process-resources), else the phase. */
    static String canonicalPhase(String phase) {
        String form = hookForm(phase);
        return MAVEN3_ALIASES.getOrDefault(form, form);
    }

    /** The phase a hook belongs to: before:X / after:X -> X. */
    static String hookBase(String phase) {
        for (String prefix : List.of("before:", "after:")) {
            if (phase.startsWith(prefix)) {
                return phase.substring(prefix.length());
            }
        }
        return phase;
    }

    private static boolean hasHookMember(List<MojoExecution> executions, String base) {
        return executions.stream().anyMatch(m -> m.getLifecyclePhase() != null
                && !hookForm(m.getLifecyclePhase()).equals(base) && hookBase(hookForm(m.getLifecyclePhase())).equals(base));
    }

    private Map<String, Object> execution(MavenSession session, MavenProject project, MojoExecution m,
                                          PathNormalizer normalizer) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", StateFiles.executionId(m));
        e.put("plugin", m.getGroupId() + ":" + m.getArtifactId());
        e.put("version", m.getVersion());
        e.put("goal", m.getGoal());
        e.put("executionId", m.getExecutionId());
        e.put("phase", m.getLifecyclePhase());
        e.put("invocation", StateFiles.executionId(m)); // groupId:artifactId:version:goal@executionId, runnable as-is
        Optional<MojoCacheDescriptor> descriptor = registry.find(m, session);
        e.put("declaredBy", descriptor.map(MojoCacheDescriptor::source).orElse(null));
        e.put("stamping", descriptor.map(d -> d.stampingCarry() ? "carry" : "exact").orElse("exact"));

        List<Object> inputs = new ArrayList<>();
        List<Object> outputs = new ArrayList<>();
        String reason = null;
        String fingerprint = null;
        if (descriptor.isEmpty()) {
            reason = "no plugin-io declaration";
        } else if (!descriptor.get().cacheable()) {
            reason = descriptor.get().notCacheableReason();
        } else if (m.getMojoDescriptor() != null && m.getMojoDescriptor().isAggregator()) {
            reason = "aggregator mojo";
        } else if (m.getForkedExecutions() != null && !m.getForkedExecutions().isEmpty()) {
            reason = "forks a lifecycle";
        } else {
            ExecutionContext ctx = new ExecutionContext(session, project, m, normalizer, true);
            try {
                fingerprint = CacheKeyCalculator.fingerprint(ctx, descriptor.get());
                for (InputSpec spec : descriptor.get().inputs()) {
                    for (ResolvedInput in : spec.resolve(ctx)) {
                        inputs.add(input(in, normalizer));
                    }
                }
                for (OutputSpec spec : descriptor.get().outputs()) {
                    for (ResolvedOutput out : spec.resolve(ctx)) {
                        outputs.add(output(out, normalizer));
                    }
                }
            } catch (NotCacheableException | RuntimeException ex) {
                reason = "cannot describe: " + ex.getMessage();
                inputs.clear();
                outputs.clear();
            }
        }
        e.put("cacheable", reason == null);
        e.put("fingerprint", reason == null ? fingerprint : null);
        e.put("reason", reason);
        e.put("inputs", inputs);
        e.put("outputs", outputs);
        e.put("stateFile", path(normalizer, StateFiles.file(project, m)));
        return e;
    }

    private static Map<String, Object> input(ResolvedInput in, PathNormalizer n) {
        Map<String, Object> i = new LinkedHashMap<>();
        i.put("name", in.name());
        i.put("kind", in.kind().name().toLowerCase(java.util.Locale.ROOT));
        switch (in.kind()) {
            case FILES -> {
                List<String> paths = new ArrayList<>();
                in.paths().forEach(p -> paths.add(path(n, p)));
                i.put("paths", paths);
                i.put("includes", in.includes());
                i.put("excludes", in.excludes());
            }
            case CLASSPATH -> i.put("scope", in.scope());
            case VALUE -> i.put("value", tokens(in.value()));
        }
        return i;
    }

    private static Map<String, Object> output(ResolvedOutput out, PathNormalizer n) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("name", out.name());
        o.put("path", path(n, out.path()));
        o.put("includes", out.includes());
        o.put("excludes", out.excludes());
        if (out.observed()) {
            // Known only after the run: the exact files are listed as `output` records in the stateFile.
            o.put("observed", true);
        }
        return o;
    }

    private static String path(PathNormalizer n, Path p) {
        return tokens(n.normalize(p.toAbsolutePath().normalize().toString()));
    }

    /** Internal tokens to the graph's neutral placeholders. */
    private static String tokens(String normalized) {
        if (normalized == null) {
            return null;
        }
        return normalized.replace(PathNormalizer.BASEDIR, TaskGraphFormat.PROJECT_ROOT)
                .replace(PathNormalizer.ROOT, TaskGraphFormat.WORKSPACE_ROOT)
                .replace(PathNormalizer.M2REPO, TaskGraphFormat.LOCAL_REPOSITORY);
    }

    private Map<String, Object> newGraph(MavenSession session) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("format", TaskGraphFormat.FORMAT);
        g.put("version", TaskGraphFormat.VERSION);
        g.put("workspaceRoot", root(session).toString());
        g.put("maven", runtime.getMavenVersion());
        g.put("goals", new ArrayList<>(session.getGoals()));
        g.put("projects", new ArrayList<>());
        return g;
    }

    synchronized void write(MavenSession session, Path file) {
        Map<String, Object> graph = graphs.remove(session);
        if (graph == null) {
            graph = newGraph(session);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.write(graph), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        LOG.info("[mojo-io] GRAPH written to {}", file);
    }

    private static Path root(MavenSession session) {
        return session.getRequest().getMultiModuleProjectDirectory() != null
                ? session.getRequest().getMultiModuleProjectDirectory().toPath().toAbsolutePath().normalize()
                : session.getTopLevelProject().getBasedir().toPath().toAbsolutePath().normalize();
    }
}
