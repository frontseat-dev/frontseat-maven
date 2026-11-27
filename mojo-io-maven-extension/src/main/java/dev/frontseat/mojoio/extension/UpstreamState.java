package dev.frontseat.mojoio.extension;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;

/**
 * With {@code mojoio.only}, the other reactor projects run nothing and only replay recorded state. How
 * much of it may be replayed depends on what the selected project's mojos need from each of them, by
 * the same rules as the task graph's dependsOn: compiling needs a dependency "ready" (its classes),
 * packaging needs runtime dependencies packaged, an attached artifact needs its project packaged.
 *
 * <p>State beyond that is not trusted. In a build system's workspace it may be left over from an
 * earlier build (an old jar next to freshly restored classes), or be rewritten at this moment by a
 * task running in parallel.
 */
final class UpstreamState {

    private UpstreamState() {}

    /**
     * Per reactor dependency of {@code project} (direct or transitive, as {@code groupId:artifactId}):
     * the phase its state may be replayed through for {@code plan}. A dependency no mojo of the plan
     * needs is absent.
     */
    static Map<String, String> limits(LifecycleModel model, MavenSession session, MavenProject project,
                                      List<MojoExecution> plan) {
        Map<String, MavenProject> reactor = new HashMap<>();
        session.getProjects().forEach(p -> reactor.put(p.getGroupId() + ":" + p.getArtifactId(), p));
        Set<String> phases = new HashSet<>();
        Set<String> resolved = new HashSet<>();
        for (MojoExecution m : plan) {
            String phase = StateFiles.phaseOf(m, project, model);
            if (phase != null) {
                phases.add(phase);
            }
            String scope = ForkedGoals.resolution(m);
            if (scope != null) {
                resolved.addAll(LifecycleModel.resolutionScopes(scope));
            }
        }
        Map<String, String> limits = new LinkedHashMap<>();
        Set<String> withRuntime = new HashSet<>();
        Set<String> plugins = new java.util.LinkedHashSet<>();
        for (MojoExecution m : plan) {
            plugins.addAll(TaskGraphCollector.pluginReactorDependencies(m, session));
        }
        record Step(MavenProject project, Set<String> phases, Set<String> resolved) {}
        Deque<Step> queue = new ArrayDeque<>();
        queue.add(new Step(project, phases, resolved));
        for (String id : plugins) {
            // A plugin (or plugin dependency) built in this reactor is loaded as a packaged artifact,
            // with what it needs at runtime.
            limits.put(id, later(model, limits.get(id), "package"));
            withRuntime.add(id);
            queue.add(new Step(reactor.get(id), model.doneBy("package"), Set.of("compile", "runtime")));
        }
        while (!queue.isEmpty()) {
            Step step = queue.poll();
            for (Dependency d : step.project().getDependencies()) {
                String id = d.getGroupId() + ":" + d.getArtifactId();
                if (!reactor.containsKey(id)) {
                    continue;
                }
                String scope = d.getScope() == null ? "compile" : d.getScope();
                Map<String, Object> dependency = new HashMap<>();
                dependency.put("type", d.getType() == null ? "jar" : d.getType());
                dependency.put("classifier", d.getClassifier() == null || d.getClassifier().isBlank() ? null : d.getClassifier());
                String need = null;
                boolean runs = false; // on a runtime or test classpath: its runtime dependencies come along
                for (String phase : step.phases()) {
                    for (LifecycleModel.Link link : model.links(phase)) {
                        if (link.type().equals("dependencies") && LifecycleModel.scopes(link.scope()).contains(scope)) {
                            need = later(model, need, TaskGraphCollector.upstreamPhase(dependency, link.phase()));
                            runs |= LifecycleModel.scopes(link.scope()).contains("runtime");
                        }
                    }
                }
                if (step.resolved().contains(scope)) {
                    need = later(model, need, TaskGraphCollector.upstreamPhase(dependency, "ready"));
                    runs |= step.resolved().contains("runtime");
                }
                if (need == null) {
                    continue;
                }
                String merged = later(model, limits.get(id), need);
                boolean changed = !merged.equals(limits.get(id));
                limits.put(id, merged);
                if (changed || (runs && withRuntime.add(id))) {
                    // What that project had to do to get there decides what its own dependencies owe. A
                    // project's compile does not wait for its runtime dependencies, so where its code
                    // runs they are asked for here.
                    if (runs) {
                        withRuntime.add(id);
                    }
                    queue.add(new Step(reactor.get(id), model.doneBy(merged),
                            withRuntime.contains(id) ? Set.of("compile", "runtime") : Set.of()));
                }
            }
        }
        return limits;
    }

    /** Every reactor project reachable from {@code project} through dependencies of any scope. */
    static Set<String> reactorDependencies(MavenSession session, MavenProject project) {
        Map<String, MavenProject> reactor = new HashMap<>();
        session.getProjects().forEach(p -> reactor.put(p.getGroupId() + ":" + p.getArtifactId(), p));
        Set<String> seen = new HashSet<>();
        Deque<MavenProject> queue = new ArrayDeque<>(List.of(project));
        while (!queue.isEmpty()) {
            for (Dependency d : queue.poll().getDependencies()) {
                String id = d.getGroupId() + ":" + d.getArtifactId();
                if (reactor.containsKey(id) && seen.add(id)) {
                    queue.add(reactor.get(id));
                }
            }
        }
        return seen;
    }

    private static String later(LifecycleModel model, String a, String b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return model.doneBy(b).size() > model.doneBy(a).size() ? b : a;
    }
}
