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
import java.util.Set;
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
    private final Map<MavenSession, LifecycleModel> models = new java.util.WeakHashMap<>();

    @Inject
    public TaskGraphCollector(DescriptorRegistry registry, RuntimeInformation runtime,
                              DependencyResolution dependencies) {
        this.registry = registry;
        this.runtime = runtime;
        this.dependencies = dependencies;
    }

    synchronized void add(MavenSession session, MavenProject project, List<MojoExecution> executions) {
        Map<String, Object> graph = graphs.computeIfAbsent(session, s -> newGraph(s));
        LifecycleModel model = models.computeIfAbsent(session, LifecycleModel::load);
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
        // Reactor dependencies with their scope: what Maven 4's dependencies-links point at.
        java.util.Set<String> reactor = new java.util.HashSet<>();
        session.getProjects().forEach(rp -> reactor.add(rp.getGroupId() + ":" + rp.getArtifactId()));
        List<Object> reactorDependencies = new ArrayList<>();
        for (org.apache.maven.model.Dependency d : project.getDependencies()) {
            String id = d.getGroupId() + ":" + d.getArtifactId();
            if (reactor.contains(id)) {
                Map<String, Object> dep = new LinkedHashMap<>();
                dep.put("id", id);
                dep.put("scope", d.getScope() == null ? "compile" : d.getScope());
                reactorDependencies.add(dep);
            }
        }
        p.put("dependencies", reactorDependencies);
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
                members.merge(hookBase(hookForm(model, m.getLifecyclePhase())), 1, Integer::sum);
            }
        }
        List<Object> list = new ArrayList<>();
        for (MojoExecution m : executions) {
            Map<String, Object> e = execution(session, project, m, normalizer);
            String phase = m.getLifecyclePhase();
            String base = phase == null ? null : hookBase(hookForm(model, phase));
            e.put("canonicalPhase", phase == null ? null : canonicalPhase(model, phase));
            e.put("lifecyclePhase", phase == null ? null : model.treePhase(phase));
            e.put("runTogether", base != null && members.get(base) > 1
                    && (!hookForm(model, phase).equals(base) || hasHookMember(model, executions, base)) ? base : null);
            list.add(e);
        }
        p.put("executions", list);
        @SuppressWarnings("unchecked")
        List<Object> projects = (List<Object>) graph.get("projects");
        projects.add(p);
        LOG.info("[mojo-io] GRAPH {}: {} execution(s) described, none run", project.getArtifactId(), list.size());
    }

    /** Maven 4 form of a phase: process-resources -> after:resources, generate-sources -> sources; at:X -> X. */
    static String hookForm(LifecycleModel model, String phase) {
        String p = model.aliases().getOrDefault(phase, phase);
        return p.startsWith("at:") ? p.substring(3) : p;
    }

    /** Maven 3 name of a phase where one exists (after:resources -> process-resources), else the phase. */
    static String canonicalPhase(LifecycleModel model, String phase) {
        String form = hookForm(model, phase);
        for (Map.Entry<String, String> alias : model.aliases().entrySet()) {
            if (alias.getValue().equals(form)) {
                return alias.getKey();
            }
        }
        return form;
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

    private static boolean hasHookMember(LifecycleModel model, List<MojoExecution> executions, String base) {
        return executions.stream().anyMatch(m -> m.getLifecyclePhase() != null
                && !hookForm(model, m.getLifecyclePhase()).equals(base)
                && hookBase(hookForm(model, m.getLifecyclePhase())).equals(base));
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
        LifecycleModel model = models.computeIfAbsent(session, LifecycleModel::load);
        graph.put("lifecycle", lifecycle(model));
        addTasks(graph, model);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.write(graph), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        LOG.info("[mojo-io] GRAPH written to {}", file);
    }

    private static Map<String, Object> lifecycle(LifecycleModel model) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("source", model.source());
        List<Object> phases = new ArrayList<>();
        for (LifecycleModel.Phase p : model.phases()) {
            Map<String, Object> ph = new LinkedHashMap<>();
            ph.put("name", p.name());
            ph.put("parent", p.parent());
            List<Object> links = new ArrayList<>();
            for (LifecycleModel.Link link : p.links()) {
                Map<String, Object> lk = new LinkedHashMap<>();
                lk.put("kind", link.kind());
                lk.put("type", link.type());
                if (link.scope() != null) {
                    lk.put("scope", link.scope());
                }
                lk.put("phase", link.phase());
                links.add(lk);
            }
            ph.put("links", links);
            phases.add(ph);
        }
        l.put("phases", phases);
        l.put("aliases", new java.util.TreeMap<>(model.aliases()));
        return l;
    }

    /**
     * Ready-made tasks per project: executions grouped by runTogether, else canonicalPhase. A task
     * depends on the previous task of its project and, per dependencies-link of its lifecycle phase, on
     * the last task of each matching reactor dependency that the link's phase covers (e.g. app's
     * compile on lib-core's tasks up to "ready", not its tests or packaging).
     */
    @SuppressWarnings("unchecked")
    private static void addTasks(Map<String, Object> graph, LifecycleModel model) {
        Map<String, List<Map<String, Object>>> tasksByProject = new LinkedHashMap<>();
        for (Object po : (List<Object>) graph.get("projects")) {
            Map<String, Object> p = (Map<String, Object>) po;
            String projectId = (String) p.get("id");
            Map<String, Map<String, Object>> tasks = new LinkedHashMap<>();
            for (Object eo : (List<Object>) p.get("executions")) {
                Map<String, Object> e = (Map<String, Object>) eo;
                String name = e.get("runTogether") != null ? (String) e.get("runTogether")
                        : e.get("canonicalPhase") != null ? (String) e.get("canonicalPhase") : (String) e.get("goal");
                Map<String, Object> t = tasks.computeIfAbsent(name, n -> {
                    Map<String, Object> task = new LinkedHashMap<>();
                    task.put("id", projectId + ":" + n);
                    task.put("name", n);
                    task.put("lifecyclePhase", e.get("lifecyclePhase"));
                    task.put("executions", new ArrayList<String>());
                    task.put("dependsOn", new ArrayList<String>());
                    return task;
                });
                ((List<String>) t.get("executions")).add((String) e.get("id"));
            }
            String previous = null;
            for (Map<String, Object> t : tasks.values()) {
                List<String> dependsOn = (List<String>) t.get("dependsOn");
                if (previous != null) {
                    dependsOn.add(previous);
                }
                String phase = (String) t.get("lifecyclePhase");
                for (LifecycleModel.Link link : phase == null ? List.<LifecycleModel.Link>of() : model.links(phase)) {
                    if (!link.type().equals("dependencies")) {
                        continue;
                    }
                    Set<String> done = model.doneBy(link.phase());
                    for (Object d : (List<Object>) p.get("dependencies")) {
                        Map<String, Object> dep = (Map<String, Object>) d;
                        if (!LifecycleModel.scopes(link.scope()).contains((String) dep.get("scope"))) {
                            continue;
                        }
                        String last = null;
                        for (Map<String, Object> ut : tasksByProject.getOrDefault((String) dep.get("id"), List.of())) {
                            if (done.contains((String) ut.get("lifecyclePhase"))) {
                                last = (String) ut.get("id");
                            }
                        }
                        if (last != null && !dependsOn.contains(last)) {
                            dependsOn.add(last);
                        }
                    }
                }
                previous = (String) t.get("id");
            }
            tasksByProject.put(projectId, new ArrayList<>(tasks.values()));
            p.put("tasks", new ArrayList<>(tasks.values()));
        }
    }

    private static Path root(MavenSession session) {
        return session.getRequest().getMultiModuleProjectDirectory() != null
                ? session.getRequest().getMultiModuleProjectDirectory().toPath().toAbsolutePath().normalize()
                : session.getTopLevelProject().getBasedir().toPath().toAbsolutePath().normalize();
    }
}
