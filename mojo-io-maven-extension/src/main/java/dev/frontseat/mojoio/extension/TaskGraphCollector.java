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

        // Phases that have hook phases bound in this project: Maven 3's pre-/post- brackets and
        // Maven 4's before:/after: hooks. A hook and its phase share process-local state (a started
        // server, a reserved port), so they must run in one Maven invocation.
        java.util.Set<String> hooked = new java.util.HashSet<>();
        for (MojoExecution m : executions) {
            String phase = m.getLifecyclePhase();
            if (phase != null && !hookBase(phase).equals(phase)) {
                hooked.add(hookBase(phase));
            }
        }
        List<Object> list = new ArrayList<>();
        for (MojoExecution m : executions) {
            Map<String, Object> e = execution(session, project, m, normalizer);
            String phase = m.getLifecyclePhase();
            e.put("runTogether", phase != null && hooked.contains(hookBase(phase)) ? hookBase(phase) : null);
            list.add(e);
        }
        p.put("executions", list);
        @SuppressWarnings("unchecked")
        List<Object> projects = (List<Object>) graph.get("projects");
        projects.add(p);
        LOG.info("[mojo-io] GRAPH {}: {} execution(s) described, none run", project.getArtifactId(), list.size());
    }

    /** Maven 3 brackets (clean, integration-test, site) and Maven 4 before:/after: hooks. */
    static String hookBase(String phase) {
        for (String prefix : List.of("before:", "after:")) {
            if (phase.startsWith(prefix)) {
                return phase.substring(prefix.length());
            }
        }
        for (String base : List.of("clean", "integration-test", "site")) {
            if (phase.equals("pre-" + base) || phase.equals("post-" + base)) {
                return base;
            }
        }
        return phase;
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
