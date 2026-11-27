package dev.frontseat.mojoio.extension;

import dev.frontseat.mojoio.spec.StateChange;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.LifecycleExecutionException;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionRunner;
import org.apache.maven.plugin.MojosExecutionStrategy;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.eclipse.sisu.Priority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. Replaces Maven's default {@link MojosExecutionStrategy} (higher {@link Priority} wins)
 * and wraps every mojo execution:
 *
 * <ol>
 *   <li>graph export ({@code mojoio.graph.output}): describe the executions, run none;</li>
 *   <li>a project {@code mojoio.only} does not select: run nothing;</li>
 *   <li>otherwise replay the recorded state of what does not run in this build (upstream projects, and
 *       the project's own executions outside the plan), as far as the plan needs it, then run each
 *       execution and record what it changed in the project.</li>
 * </ol>
 */
@Named
@Singleton
@Priority(10)
public class MojoIoExecutionStrategy implements MojosExecutionStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(MojoIoExecutionStrategy.class);
    private static final String PREFIX = "[mojo-io] ";

    private final TaskGraphCollector graph;
    private final MavenProjectHelper projectHelper;
    /**
     * What replay applied, per build session, project id and execution identity (see
     * StateFiles#replayPending); a project id is present once it has been replayed. Keyed by session, not
     * by MavenProject: MavenProject's equals() is by coordinates, so in a long-lived JVM (mvnd) the same
     * module from an earlier build would count as already replayed. Thread-safe for parallel builders.
     */
    private final Map<MavenSession, Map<String, Map<String, List<StateChange>>>> replayedState =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Per build and project: the identities of the executions that ran in this build, never replayed. */
    private final Map<MavenSession, Map<String, Set<String>>> executed = Collections.synchronizedMap(new WeakHashMap<>());

    /** Per build: the projects this strategy was called for. */
    private final Map<MavenSession, Set<String>> started = Collections.synchronizedMap(new WeakHashMap<>());

    private final Map<MavenSession, LifecycleModel> lifecycles = Collections.synchronizedMap(new WeakHashMap<>());

    @Inject
    public MojoIoExecutionStrategy(TaskGraphCollector graph, MavenProjectHelper projectHelper) {
        this.graph = graph;
        this.projectHelper = projectHelper;
    }

    @Override
    public void execute(List<MojoExecution> executions, MavenSession session, MojoExecutionRunner runner)
            throws LifecycleExecutionException {
        MojoIoConfig config = MojoIoConfig.from(session);
        if (!config.enabled()) {
            for (MojoExecution m : executions) {
                runner.run(m);
            }
            return;
        }
        MavenProject project = session.getCurrentProject();
        started.computeIfAbsent(session, k -> ConcurrentHashMap.newKeySet()).add(id(project));
        if (config.graphOutput() != null) {
            graph.add(session, project, executions); // dry run: describe, run nothing
            return;
        }
        if (!config.runs(project)) {
            // Only here so others can resolve it: it runs nothing. Its recorded state is replayed when a
            // selected project starts.
            LOG.info(PREFIX + "ONLY  {} not selected by mojoio.only: {} execution(s) not run", project.getArtifactId(),
                    executions.size());
            return;
        }
        if (!config.state()) {
            for (MojoExecution m : executions) {
                runner.run(m);
            }
            return;
        }
        PathNormalizer normalizer = normalizer(session, project);
        if (!config.only().isEmpty()) {
            replayUpstream(session, project, executions, config);
        }
        replayOwn(session, project, executions, normalizer);
        for (MojoExecution m : executions) {
            executeOne(m, session, project, runner, normalizer);
        }
    }

    /** Whether Maven got as far as running {@code project}'s executions in this build. */
    boolean started(MavenSession session, MavenProject project) {
        Set<String> projects = started.get(session);
        return projects != null && projects.contains(id(project));
    }

    /** Whether Maven called this strategy at all in this build. */
    boolean calledIn(MavenSession session) {
        return started.containsKey(session);
    }

    /** Runs one execution and records what it changed in the project. */
    private void executeOne(MojoExecution m, MavenSession session, MavenProject project, MojoExecutionRunner runner,
                            PathNormalizer normalizer) throws LifecycleExecutionException {
        executed(session, project).add(StateFiles.identity(StateFiles.executionId(m)));
        // System properties are JVM-wide: with modules building in parallel (-T, mvnd) another module's
        // change would be attributed to this mojo, so they are only tracked in sequential builds.
        boolean trackSystemProperties = !session.isParallel();
        ProjectState before = ProjectState.capture(project, trackSystemProperties);
        runner.run(m);
        List<StateChange> state = List.of();
        String incomplete = null;
        try {
            state = before.diff(ProjectState.capture(project, trackSystemProperties), normalizer);
            state = withReplayedEffects(session, project, m, state, normalizer);
        } catch (NotCacheableException e) {
            incomplete = e.getMessage();
        }
        StateFiles.record(project, m, state, incomplete, StateFiles.phaseOf(m, project, lifecycle(session)));
    }

    /**
     * Replays {@code project}'s recorded state of the executions that are not in {@code plan} and have
     * neither run nor been replayed in this build, through the last phase of the plan. Maven 3 calls the
     * strategy once per project; Maven 4's concurrent builder once per lifecycle step, with partial plans,
     * so later calls replay what the later steps need.
     */
    private void replayOwn(MavenSession session, MavenProject project, List<MojoExecution> plan,
                           PathNormalizer normalizer) {
        Map<String, List<StateChange>> applied = applied(session, project);
        Set<String> ran = executed(session, project);
        // State of phases after the last one in the plan is not this invocation's business. In a shared
        // workspace it is typically left over from an earlier build (an old jar's artifact file and
        // attachments, replayed into a compile task).
        LifecycleModel model = lifecycle(session);
        int last = -1;
        boolean known = !plan.isEmpty();
        for (MojoExecution m : plan) {
            int index = LifecycleModel.EXECUTION_ORDER.indexOf(String.valueOf(StateFiles.phaseOf(m, project, model)));
            known &= index >= 0;
            last = Math.max(last, index);
        }
        int through = known ? last : Integer.MAX_VALUE;
        applied.putAll(StateFiles.replayPending(project, plan, normalizer, projectHelper, state -> {
            String identity = StateFiles.identity(state.execution());
            return !applied.containsKey(identity) && !ran.contains(identity)
                    && (state.phase() == null || LifecycleModel.EXECUTION_ORDER.indexOf(state.phase()) <= through);
        }));
    }

    /**
     * {@code mojoio.only}: replays the state of the projects {@code project} builds on that this build does
     * not run, each through the phase {@code plan} needs it at (see UpstreamState). Projects it does not
     * depend on (parents) are replayed fully.
     */
    private synchronized void replayUpstream(MavenSession session, MavenProject project, List<MojoExecution> plan,
                                             MojoIoConfig config) {
        LifecycleModel model = lifecycle(session);
        Map<String, String> limits = UpstreamState.limits(model, session, project, plan);
        Set<String> dependencies = UpstreamState.reactorDependencies(session, project);
        for (MavenProject upstream : session.getProjectDependencyGraph().getUpstreamProjects(project, true)) {
            if (config.runs(upstream)) {
                continue;
            }
            String id = id(upstream);
            String limit = limits.get(id);
            if (limit == null && dependencies.contains(id)) {
                continue; // a dependency nothing in the plan needs
            }
            Set<String> through = limit == null ? null : model.doneBy(limit);
            Map<String, List<StateChange>> applied = applied(session, upstream);
            applied.putAll(StateFiles.replayPending(upstream, List.of(), normalizer(session, upstream), projectHelper,
                    state -> !applied.containsKey(StateFiles.identity(state.execution()))
                            && (through == null || state.phase() == null || through.contains(state.phase()))));
        }
    }

    /**
     * If this execution's recorded state was replayed earlier in this build (it was not in the plan seen
     * then), the diff around its run misses those changes: they were already there. Records that still
     * hold are its own effects and are added back.
     */
    private List<StateChange> withReplayedEffects(MavenSession session, MavenProject project, MojoExecution m,
                                                  List<StateChange> diff, PathNormalizer normalizer) {
        List<StateChange> earlier = applied(session, project).remove(StateFiles.identity(StateFiles.executionId(m)));
        if (earlier == null || earlier.isEmpty()) {
            return diff;
        }
        List<StateChange> merged = new ArrayList<>(diff);
        for (StateChange r : earlier) {
            boolean covered = diff.stream().anyMatch(d -> d.kind().equals(r.kind()) && Objects.equals(d.name(), r.name()));
            if (!covered && ProjectState.holds(r, project, normalizer)) {
                merged.add(r);
            }
        }
        return merged;
    }

    private Map<String, List<StateChange>> applied(MavenSession session, MavenProject project) {
        return replayedState.computeIfAbsent(session, s -> new ConcurrentHashMap<>())
                .computeIfAbsent(id(project), k -> new ConcurrentHashMap<>());
    }

    private Set<String> executed(MavenSession session, MavenProject project) {
        return executed.computeIfAbsent(session, s -> new ConcurrentHashMap<>())
                .computeIfAbsent(id(project), k -> ConcurrentHashMap.newKeySet());
    }

    private LifecycleModel lifecycle(MavenSession session) {
        return lifecycles.computeIfAbsent(session, LifecycleModel::load);
    }

    private static String id(MavenProject project) {
        return project.getGroupId() + ":" + project.getArtifactId();
    }

    static PathNormalizer normalizer(MavenSession session, MavenProject project) {
        Path root = session.getRequest().getMultiModuleProjectDirectory() != null
                ? session.getRequest().getMultiModuleProjectDirectory().toPath()
                : session.getTopLevelProject().getBasedir().toPath();
        Path repo = Path.of(session.getRepositorySession().getLocalRepository().getBasedir().getAbsolutePath());
        return new PathNormalizer(project.getBasedir().toPath(), root, repo);
    }
}
