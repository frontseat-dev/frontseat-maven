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
        // Reactor projects named in plugin configuration (a Tycho target definition, a build-tools
        // module): needed in the reactor, though no dependency says so, so "-am" leaves them out.
        java.util.Set<String> reactorIds = new java.util.HashSet<>();
        session.getProjects().forEach(rp -> reactorIds.add(rp.getGroupId() + ":" + rp.getArtifactId()));
        List<String> references = configurationReferences(project, reactorIds);
        p.put("configurationReferences", references);
        for (String r : references) {
            if (!dependsOn.contains(r)) {
                dependsOn.add(r);
            }
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
                dep.put("type", d.getType() == null ? "jar" : d.getType());
                dep.put("classifier", d.getClassifier() == null || d.getClassifier().isBlank() ? null : d.getClassifier());
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
        List<String> loadFiles = new ArrayList<>();
        for (Path f : loadFiles(project, session)) {
            loadFiles.add(path(normalizer, f));
        }
        p.put("loadFiles", loadFiles);
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

    /** Reactor projects other than {@code project} named by coordinates in its build plugins' configuration. */
    static List<String> configurationReferences(MavenProject project, java.util.Set<String> reactorIds) {
        java.util.Set<String> found = new java.util.LinkedHashSet<>();
        for (org.apache.maven.model.Plugin plugin : project.getBuildPlugins()) {
            collectReferences(plugin.getConfiguration(), reactorIds, found);
            for (org.apache.maven.model.PluginExecution e : plugin.getExecutions()) {
                collectReferences(e.getConfiguration(), reactorIds, found);
            }
        }
        found.remove(project.getGroupId() + ":" + project.getArtifactId());
        return new ArrayList<>(found);
    }

    private static void collectReferences(Object configuration, java.util.Set<String> reactorIds, java.util.Set<String> found) {
        if (!(configuration instanceof org.codehaus.plexus.util.xml.Xpp3Dom node)) {
            return;
        }
        org.codehaus.plexus.util.xml.Xpp3Dom g = node.getChild("groupId");
        org.codehaus.plexus.util.xml.Xpp3Dom a = node.getChild("artifactId");
        if (g != null && a != null && g.getValue() != null && a.getValue() != null) {
            String id = g.getValue().trim() + ":" + a.getValue().trim();
            if (reactorIds.contains(id)) {
                found.add(id);
            }
        }
        for (org.codehaus.plexus.util.xml.Xpp3Dom child : node.getChildren()) {
            collectReferences(child, reactorIds, found);
        }
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
        // Maven resolves the project's dependencies of this scope before the mojo runs, so the reactor
        // dependencies in it must have been built, whatever the lifecycle's links say.
        String resolution = ForkedGoals.resolution(m);
        e.put("requiresDependencyResolution", resolution == null || resolution.isBlank() ? null : resolution);
        // Plugins and plugin dependencies built in this reactor: needed packaged before the mojo runs.
        e.put("pluginReactorDependencies", UpstreamState.pluginReactorDependencies(m, session));
        // Invoked as a goal, Maven runs an aggregator once, for the build's top-level project, whatever
        // project it is bound in.
        e.put("aggregator", m.getMojoDescriptor() != null && m.getMojoDescriptor().isAggregator());
        e.put("invocation", StateFiles.executionId(m)); // groupId:artifactId:version:goal@executionId, runnable as-is
        Optional<MojoCacheDescriptor> descriptor = registry.find(m, session)
                .map(d -> ForkedGoals.merge(m, project, session, registry, d));
        e.put("declaredBy", descriptor.map(MojoCacheDescriptor::source).orElse(null));
        e.put("stamping", descriptor.map(d -> d.stampingCarry() ? "carry" : "exact").orElse("exact"));
        // Bookkeeping the execution (or Maven itself) writes in the build directory: not outputs. Build
        // systems must leave these out of observed outputs, or they make hashes unstable.
        List<String> ignoredWrites = new ArrayList<>(WriteSnapshot.MAVEN_WRITES);
        descriptor.ifPresent(d -> ignoredWrites.addAll(d.ignoredWrites()));
        e.put("ignoredWrites", ignoredWrites);

        List<Object> inputs = new ArrayList<>();
        List<Object> outputs = new ArrayList<>();
        String reason = null;
        String fingerprint = null;
        if (descriptor.isEmpty()) {
            reason = "no plugin-io declaration";
        } else if (!descriptor.get().cacheable()) {
            reason = descriptor.get().notCacheableReason();
        } else if (m.getMojoDescriptor() != null && m.getMojoDescriptor().isAggregator()
                && !descriptor.get().reactorWide()) {
            reason = "aggregator mojo without a reactorModels input";
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

    synchronized void write(MavenSession session, Path file, MojoIoConfig.Granularity granularity) {
        Map<String, Object> graph = graphs.remove(session);
        if (graph == null) {
            graph = newGraph(session);
        }
        LifecycleModel model = models.computeIfAbsent(session, LifecycleModel::load);
        graph.put("lifecycle", lifecycle(model));
        graph.put("granularity", granularity.name().toLowerCase(java.util.Locale.ROOT));
        addTasks(graph, model, granularity);
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
     * Ready-made tasks per project. Executions are grouped by granularity: {@code phase} (runTogether
     * group, else canonicalPhase), {@code stage} (compile, test, package, verify; see
     * {@link LifecycleModel#stage}) or {@code module} (one task, "build"). A task depends on the previous
     * task of its project and, per dependencies-link of each of its lifecycle phases, on the task of each
     * matching reactor dependency that completes the link's phase (e.g. app's compile on lib-core's
     * tasks up to "ready", not its tests or packaging).
     */
    @SuppressWarnings("unchecked")
    private static void addTasks(Map<String, Object> graph, LifecycleModel model, MojoIoConfig.Granularity granularity) {
        Map<String, List<Map<String, Object>>> tasksByProject = new LinkedHashMap<>();
        Map<String, List<Object>> dependenciesByProject = new java.util.HashMap<>();
        for (Object po : (List<Object>) graph.get("projects")) {
            dependenciesByProject.put((String) ((Map<String, Object>) po).get("id"),
                    (List<Object>) ((Map<String, Object>) po).get("dependencies"));
        }
        for (Object po : (List<Object>) graph.get("projects")) {
            Map<String, Object> p = (Map<String, Object>) po;
            String projectId = (String) p.get("id");
            Map<String, Map<String, Object>> tasks = new LinkedHashMap<>();
            boolean splitValidate = false;
            for (Object eo : (List<Object>) p.get("executions")) {
                Map<String, Object> e = (Map<String, Object>) eo;
                String ph = (String) e.get("lifecyclePhase");
                if (ph != null && isPreparation(model, ph) && !Boolean.TRUE.equals(e.get("cacheable"))) {
                    splitValidate = true;
                }
            }
            for (Object eo : (List<Object>) p.get("executions")) {
                Map<String, Object> e = (Map<String, Object>) eo;
                String phase = (String) e.get("lifecyclePhase");
                String fine = e.get("runTogether") != null ? (String) e.get("runTogether")
                        : e.get("canonicalPhase") != null ? (String) e.get("canonicalPhase") : (String) e.get("goal");
                String stage = phase == null ? null : model.stage(phase);
                // Mojos that can't be cached, bound before the sources (a build qualifier from the clock,
                // git metadata): a task of their own, so they don't make the compile stage uncacheable.
                // Their effects reach the next task through the state file.
                if (stage != null && stage.equals("compile") && splitValidate && isPreparation(model, phase)) {
                    stage = "validate";
                }
                String name = switch (granularity) {
                    case PHASE -> fine;
                    case STAGE -> stage != null ? stage : fine;
                    case MODULE -> "build";
                };
                Map<String, Object> t = tasks.computeIfAbsent(name, n -> {
                    Map<String, Object> task = new LinkedHashMap<>();
                    task.put("id", projectId + ":" + n);
                    task.put("name", n);
                    task.put("lifecyclePhase", phase);
                    task.put("lifecyclePhases", new ArrayList<String>());
                    task.put("executions", new ArrayList<String>());
                    task.put("dependsOn", new ArrayList<String>());
                    return task;
                });
                ((List<String>) t.get("executions")).add((String) e.get("id"));
                List<String> phases = (List<String>) t.get("lifecyclePhases");
                if (phase != null && !phases.contains(phase)) {
                    phases.add(phase);
                }
            }
            Map<String, Map<String, Object>> executionsById = new java.util.HashMap<>();
            for (Object eo : (List<Object>) p.get("executions")) {
                executionsById.put((String) ((Map<String, Object>) eo).get("id"), (Map<String, Object>) eo);
            }
            String previous = null;
            for (Map<String, Object> t : tasks.values()) {
                List<String> dependsOn = (List<String>) t.get("dependsOn");
                if (previous != null) {
                    dependsOn.add(previous);
                }
                // What the task's mojos make Maven resolve: those reactor dependencies must be "ready",
                // even where no link of the task's phases asks for them (e.g. a check in the compile
                // stage that resolves the test classpath).
                Set<String> resolved = new java.util.HashSet<>();
                for (String id : (List<String>) t.get("executions")) {
                    String scope = (String) executionsById.get(id).get("requiresDependencyResolution");
                    if (scope != null) {
                        resolved.addAll(LifecycleModel.resolutionScopes(scope));
                    }
                }
                for (Object d : (List<Object>) p.get("dependencies")) {
                    Map<String, Object> dep = (Map<String, Object>) d;
                    if (!resolved.contains((String) dep.get("scope"))) {
                        continue;
                    }
                    addUpstream(dependsOn, tasksByProject, (String) dep.get("id"), model.doneBy(upstreamPhase(dep, "ready")));
                    closure((String) dep.get("id"), resolved.contains("runtime"), dependenciesByProject).forEach((id, via) ->
                            addUpstream(dependsOn, tasksByProject, id, model.doneBy(upstreamPhase(via, "ready"))));
                }
                for (String id : (List<String>) t.get("executions")) {
                    for (String plugin : (List<String>) executionsById.get(id).get("pluginReactorDependencies")) {
                        addUpstream(dependsOn, tasksByProject, plugin, model.doneBy("package"));
                        closure(plugin, true, dependenciesByProject).forEach((member, via) ->
                                addUpstream(dependsOn, tasksByProject, member, model.doneBy("package")));
                    }
                }
                for (String phase : (List<String>) t.get("lifecyclePhases")) {
                    for (LifecycleModel.Link link : model.links(phase)) {
                        if (!link.type().equals("dependencies")) {
                            continue;
                        }
                        for (Object d : (List<Object>) p.get("dependencies")) {
                            Map<String, Object> dep = (Map<String, Object>) d;
                            if (!LifecycleModel.scopes(link.scope()).contains((String) dep.get("scope"))) {
                                continue;
                            }
                            addUpstream(dependsOn, tasksByProject, (String) dep.get("id"),
                                    model.doneBy(upstreamPhase(dep, link.phase())));
                            closure((String) dep.get("id"), LifecycleModel.scopes(link.scope()).contains("runtime"),
                                    dependenciesByProject).forEach((id, via) -> addUpstream(dependsOn, tasksByProject,
                                            id, model.doneBy(upstreamPhase(via, link.phase()))));
                        }
                    }
                }
                previous = (String) t.get("id");
            }
            tasksByProject.put(projectId, new ArrayList<>(tasks.values()));
            p.put("tasks", new ArrayList<>(tasks.values()));
        }
        // The reactor projects a task needs, transitively, beyond what Maven's "-am" finds: those named in
        // configuration, and dependencies added after the reactor was sorted (Eclipse Tycho adds the
        // bundles, features and units a project requires as dependencies while loading it).
        Map<String, Object> byId = new LinkedHashMap<>();
        for (Object po : (List<Object>) graph.get("projects")) {
            byId.put((String) ((Map<String, Object>) po).get("id"), po);
        }
        for (Object po : (List<Object>) graph.get("projects")) {
            Map<String, Object> p = (Map<String, Object>) po;
            java.util.Set<String> closure = new java.util.LinkedHashSet<>();
            java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(List.of((String) p.get("id")));
            while (!queue.isEmpty()) {
                Map<String, Object> q = (Map<String, Object>) byId.get(queue.poll());
                if (q == null) {
                    continue;
                }
                List<String> next = new ArrayList<>((List<String>) q.getOrDefault("configurationReferences", List.of()));
                for (Object d : (List<Object>) q.get("dependencies")) {
                    next.add((String) ((Map<String, Object>) d).get("id"));
                }
                for (String n : next) {
                    if (!n.equals(p.get("id")) && closure.add(n)) {
                        queue.add(n);
                    }
                }
            }
            p.put("reactorReferences", new ArrayList<>(closure));
        }
        // Reactor projects named in configuration: every task waits for all of that project's tasks. A
        // second pass, because the reactor order does not know about these references.
        for (Object po : (List<Object>) graph.get("projects")) {
            Map<String, Object> p = (Map<String, Object>) po;
            for (String ref : (List<String>) p.getOrDefault("configurationReferences", List.of())) {
                List<Map<String, Object>> refTasks = tasksByProject.getOrDefault(ref, List.of());
                if (refTasks.isEmpty()) {
                    continue;
                }
                String last = (String) refTasks.get(refTasks.size() - 1).get("id");
                for (Map<String, Object> t : tasksByProject.getOrDefault((String) p.get("id"), List.of())) {
                    List<String> dependsOn = (List<String>) t.get("dependsOn");
                    if (!dependsOn.contains(last)) {
                        dependsOn.add(last);
                    }
                }
            }
        }
    }

    /** validate and initialize: what runs before a project's sources. */
    /**
     * The files of {@code project} that build extensions read while Maven loads it, from their
     * {@code projectLoad} declarations. Not under the build directory or a nested module.
     */
    private List<Path> loadFiles(MavenProject project, MavenSession session) {
        List<String> patterns = new ArrayList<>();
        for (org.apache.maven.model.Plugin plugin : project.getBuildPlugins()) {
            patterns.addAll(registry.projectLoad(plugin.getGroupId() + ":" + plugin.getArtifactId(), session));
        }
        if (patterns.isEmpty() || project.getBasedir() == null) {
            return List.of();
        }
        Path base = project.getBasedir().toPath().toAbsolutePath().normalize();
        Path build = Path.of(project.getBuild().getDirectory()).toAbsolutePath().normalize();
        Set<Path> modules = new java.util.HashSet<>();
        for (String m : project.getModules()) {
            modules.add(base.resolve(m).normalize());
        }
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(base, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes a) {
                    boolean skip = !dir.equals(base) && (dir.equals(build) || modules.contains(dir)
                            || dir.getFileName().toString().equals(".git"));
                    return skip ? java.nio.file.FileVisitResult.SKIP_SUBTREE : java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes a) {
                    String rel = base.relativize(file).toString().replace('\\', '/');
                    if (a.isRegularFile() && dev.frontseat.mojoio.cache.AntPattern.selected(rel, patterns, List.of())) {
                        files.add(file);
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        files.sort(null);
        return files;
    }

    private static boolean isPreparation(LifecycleModel model, String treePhase) {
        return treePhase.equals("validate") || treePhase.equals("initialize");
    }

    /** Adds the last task of {@code projectId} with work in {@code done} (the phases a link waits for). */
    @SuppressWarnings("unchecked")
    private static void addUpstream(List<String> dependsOn, Map<String, List<Map<String, Object>>> tasksByProject,
                                    String projectId, Set<String> done) {
        String last = null;
        for (Map<String, Object> ut : tasksByProject.getOrDefault(projectId, List.of())) {
            if (((List<String>) ut.get("lifecyclePhases")).stream().anyMatch(done::contains)) {
                last = (String) ut.get("id");
            }
        }
        if (last != null && !dependsOn.contains(last)) {
            dependsOn.add(last);
        }
    }

    /**
     * Reactor projects that come with {@code projectId} on a classpath, transitively, each with the
     * dependency declaration it was reached through: its compile-scoped dependencies, and where code
     * runs ({@code runtime}) its runtime-scoped ones too. The project itself is not included.
     *
     * <p>A project's own tasks do not reach these: its compile task does not wait for its runtime
     * dependencies, and a pom project in between may have no task at all.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> closure(String projectId, boolean runtime,
                                                    Map<String, List<Object>> dependenciesByProject) {
        Set<String> scopes = runtime ? Set.of("compile", "runtime") : Set.of("compile");
        Map<String, Map<String, Object>> seen = new LinkedHashMap<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(List.of(projectId));
        while (!queue.isEmpty()) {
            for (Object d : dependenciesByProject.getOrDefault(queue.poll(), List.of())) {
                Map<String, Object> dep = (Map<String, Object>) d;
                String id = (String) dep.get("id");
                if (!scopes.contains((String) dep.get("scope")) || id.equals(projectId)) {
                    continue;
                }
                Map<String, Object> known = seen.get(id);
                // An attached artifact or archive asks for more (the packaged project): keep that one.
                if (known == null || (upstreamPhase(known, "ready").equals("ready") && !upstreamPhase(dep, "ready").equals("ready"))) {
                    seen.put(id, dep);
                    if (known == null) {
                        queue.add(id);
                    }
                }
            }
        }
        return seen;
    }

    /** Dependency types whose artifact Maven can take from the upstream project's classes directory. */
    private static final Set<String> RESOLVABLE_WHEN_READY = Set.of("jar", "pom", "bundle", "maven-plugin", "ejb");

    /**
     * The upstream phase a dependency needs. Where the lifecycle asks for "ready" (classes compiled),
     * that only serves the plain main artifact. Anything else only exists once the upstream project is
     * packaged: an attached artifact (any classifier: tests, sources, javadoc, …; or a type that implies
     * one: test-jar, java-source, javadoc, ejb-client) and archives such as war or zip.
     */
    static String upstreamPhase(Map<String, Object> dependency, String phase) {
        boolean packaged = dependency.get("classifier") != null
                || !RESOLVABLE_WHEN_READY.contains(String.valueOf(dependency.get("type")));
        return packaged && phase.equals("ready") ? "package" : phase;
    }

    private static Path root(MavenSession session) {
        return session.getRequest().getMultiModuleProjectDirectory() != null
                ? session.getRequest().getMultiModuleProjectDirectory().toPath().toAbsolutePath().normalize()
                : session.getTopLevelProject().getBasedir().toPath().toAbsolutePath().normalize();
    }
}
