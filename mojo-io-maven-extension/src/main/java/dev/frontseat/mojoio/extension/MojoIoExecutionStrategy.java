package dev.frontseat.mojoio.extension;

import dev.frontseat.mojoio.cache.CacheEntry;
import dev.frontseat.mojoio.cache.CacheKey;
import dev.frontseat.mojoio.cache.LocalCache;
import dev.frontseat.mojoio.cache.Restamping;
import dev.frontseat.mojoio.cache.Stamps;
import dev.frontseat.mojoio.extension.descriptor.DescriptorRegistry;
import dev.frontseat.mojoio.extension.descriptor.MojoCacheDescriptor;
import dev.frontseat.mojoio.cache.FileTrees;
import dev.frontseat.mojoio.extension.descriptor.OutputSpec;
import dev.frontseat.mojoio.extension.descriptor.ResolvedOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.WeakHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.LifecycleExecutionException;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionRunner;
import org.apache.maven.plugin.MojosExecutionStrategy;
import org.apache.maven.project.MavenProject;
import org.eclipse.sisu.Priority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. Replaces Maven's default {@link MojosExecutionStrategy} (higher {@link Priority} wins)
 * and wraps every mojo execution:
 *
 * <ol>
 *   <li>no descriptor, or not cacheable: run normally;</li>
 *   <li>otherwise resolve the mojo's dependencies, compute the cache key and look it up;</li>
 *   <li>hit: restore the output files and replay the project-state diff, skip the mojo;</li>
 *   <li>miss: snapshot project state, run, then store declared outputs plus the state diff.</li>
 * </ol>
 *
 * Anything unexpected while computing keys or storing falls back to plain execution; the cache must
 * never be the reason a build fails.
 */
@Named
@Singleton
@Priority(10)
public class MojoIoExecutionStrategy implements MojosExecutionStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(MojoIoExecutionStrategy.class);
    private static final String PREFIX = "[mojo-io] ";

    private final DescriptorRegistry registry;
    private final DependencyResolution dependencyResolution;
    private final PrerequisiteExpander prerequisites;
    private final TaskGraphCollector graph;
    private final org.apache.maven.project.MavenProjectHelper projectHelper;
    /**
     * What replay applied, per build session, project id and execution identity (see
     * StateFiles#replayPending); a project id is present once it has been replayed. Keyed by session, not
     * by MavenProject: MavenProject's equals() is by coordinates, so in a long-lived JVM (mvnd) the same
     * module from an earlier build would count as already replayed. Thread-safe for parallel builders.
     */
    private final Map<MavenSession, Map<String, Map<String, List<CacheEntry.StateRecord>>>> replayedState =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Per build and project: the executions that ran or were restored in this build, never replayed. */
    private static final class ProjectRun {
        final Set<String> executed = java.util.concurrent.ConcurrentHashMap.newKeySet();
    }

    private final Map<MavenSession, Map<String, ProjectRun>> runs = Collections.synchronizedMap(new WeakHashMap<>());

    private ProjectRun run(MavenSession session, MavenProject project) {
        return runs.computeIfAbsent(session, s -> new java.util.concurrent.ConcurrentHashMap<>())
                .computeIfAbsent(project.getGroupId() + ":" + project.getArtifactId(), k -> new ProjectRun());
    }

    /**
     * Replays {@code project}'s recorded state that {@code plan} may rely on and that was not applied yet.
     * Maven 3 calls the strategy once per project; Maven 4's concurrent builder once per lifecycle step,
     * with partial plans, so later calls replay what the later steps need.
     */
    private void replayOwn(MavenSession session, MavenProject project, List<MojoExecution> plan,
                           PathNormalizer normalizer) {
        Map<String, Map<String, List<CacheEntry.StateRecord>>> projects =
                replayedState.computeIfAbsent(session, s -> new java.util.concurrent.ConcurrentHashMap<>());
        String id = project.getGroupId() + ":" + project.getArtifactId();
        Map<String, List<CacheEntry.StateRecord>> applied =
                projects.computeIfAbsent(id, k -> new java.util.concurrent.ConcurrentHashMap<>());
        Set<String> executed = run(session, project).executed;
        // State of phases after the last one in the plan is not this invocation's business. In a build
        // system's workspace it is typically left over from an earlier build (an old jar's artifact
        // file and attachments, replayed into a compile task).
        LifecycleModel model = lifecycle(session);
        int last = -1;
        boolean known = !plan.isEmpty();
        for (MojoExecution m : plan) {
            int index = LifecycleModel.EXECUTION_ORDER.indexOf(String.valueOf(UpstreamState.phaseOf(m, project, model)));
            known &= index >= 0;
            last = Math.max(last, index);
        }
        int through = known ? last : Integer.MAX_VALUE;
        applied.putAll(StateFiles.replayPending(project, plan, normalizer, projectHelper, state -> {
            String identity = StateFiles.identity(state.execution());
            return !applied.containsKey(identity) && !executed.contains(identity)
                    && (state.phase() == null || LifecycleModel.EXECUTION_ORDER.indexOf(state.phase()) <= through);
        }));
    }

    /** Per build: the projects this strategy was called for. */
    private final Map<MavenSession, Set<String>> started = Collections.synchronizedMap(new WeakHashMap<>());

    /** Whether Maven got as far as running {@code project}'s executions in this build. */
    boolean started(MavenSession session, MavenProject project) {
        Set<String> projects = started.get(session);
        return projects != null && projects.contains(project.getGroupId() + ":" + project.getArtifactId());
    }

    /** Whether Maven called this strategy at all in this build. */
    boolean calledIn(MavenSession session) {
        return started.containsKey(session);
    }

    private final Map<MavenSession, LifecycleModel> lifecycles = Collections.synchronizedMap(new WeakHashMap<>());

    private LifecycleModel lifecycle(MavenSession session) {
        return lifecycles.computeIfAbsent(session, LifecycleModel::load);
    }

    /**
     * {@code mojoio.only}: replays the state of the projects {@code project} builds on, each through
     * the phase {@code plan} needs it at. Projects it does not depend on (parents) are replayed fully.
     */
    private synchronized void replayUpstream(MavenSession session, MavenProject project, List<MojoExecution> plan,
                                             MojoIoConfig config) {
        LifecycleModel model = lifecycle(session);
        Map<String, String> limits = UpstreamState.limits(model, session, project, plan);
        Set<String> dependencies = UpstreamState.reactorDependencies(session, project);
        Map<String, Map<String, List<CacheEntry.StateRecord>>> projects =
                replayedState.computeIfAbsent(session, s -> new java.util.concurrent.ConcurrentHashMap<>());
        for (MavenProject upstream : session.getProjectDependencyGraph().getUpstreamProjects(project, true)) {
            if (config.runs(upstream)) {
                continue;
            }
            String id = upstream.getGroupId() + ":" + upstream.getArtifactId();
            String limit = limits.get(id);
            if (limit == null && dependencies.contains(id)) {
                continue; // a dependency nothing in the plan needs
            }
            Set<String> through = limit == null ? null : model.doneBy(limit);
            Map<String, List<CacheEntry.StateRecord>> applied =
                    projects.computeIfAbsent(id, k -> new java.util.concurrent.ConcurrentHashMap<>());
            PathNormalizer normalizer = normalizer(session, upstream).withStamps(StampValues.of(session, upstream));
            applied.putAll(StateFiles.replayPending(upstream, List.of(), normalizer, projectHelper,
                    state -> !applied.containsKey(StateFiles.identity(state.execution()))
                            && (through == null || state.phase() == null || through.contains(state.phase()))));
        }
    }

    @Inject
    public MojoIoExecutionStrategy(DescriptorRegistry registry, DependencyResolution dependencyResolution,
                                   PrerequisiteExpander prerequisites, TaskGraphCollector graph,
                                   org.apache.maven.project.MavenProjectHelper projectHelper) {
        this.projectHelper = projectHelper;
        this.registry = registry;
        this.dependencyResolution = dependencyResolution;
        this.prerequisites = prerequisites;
        this.graph = graph;
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
        started.computeIfAbsent(session, k -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                .add(project.getGroupId() + ":" + project.getArtifactId());
        if (config.graphOutput() != null) {
            graph.add(session, project, executions); // dry run: describe, run nothing
            return;
        }
        List<MojoExecution> plan = config.prerequisites()
                ? prerequisites.expand(executions, session, project)
                : executions;
        PathNormalizer normalizer = normalizer(session, project).withStamps(StampValues.of(session, project));
        if (!config.runs(project)) {
            // Only here so others can resolve it: it runs nothing. Its recorded state is replayed when a
            // selected project starts, through the phase that project needs it at (see UpstreamState).
            LOG.info(PREFIX + "ONLY  {} not selected by mojoio.only: {} execution(s) not run", project.getArtifactId(),
                    plan.size());
            return;
        }
        if (config.state()) {
            if (!config.only().isEmpty()) {
                replayUpstream(session, project, plan, config);
            }
            replayOwn(session, project, plan, normalizer);
        }
        LocalCache cache = config.cache() ? new LocalCache(config.cacheDir()) : null;
        for (MojoExecution m : plan) {
            executeOne(m, session, project, runner, cache, config, normalizer);
        }
    }

    /**
     * One execution: restore it from the cache if possible, otherwise run it; then record what it
     * changed in the project (for every execution, declared or not).
     */
    private void executeOne(MojoExecution m, MavenSession session, MavenProject project, MojoExecutionRunner runner,
                            LocalCache cache, MojoIoConfig config, PathNormalizer normalizer)
            throws LifecycleExecutionException {
        String label = label(m, project);
        ProjectRun projectRun = run(session, project);
        projectRun.executed.add(StateFiles.identity(StateFiles.executionId(m)));
        Optional<MojoCacheDescriptor> descriptor = registry.find(m, session)
                .map(d -> ForkedGoals.merge(m, project, session, registry, d));
        // `normalizer` tokenizes stamps (used for paths and state). The key only does so for mojos that
        // merely carry stamp values; for the others the values stay in the key.
        boolean carry = descriptor.map(MojoCacheDescriptor::stampingCarry).orElse(false);
        ExecutionContext ctx = new ExecutionContext(session, project, m,
                carry ? normalizer : normalizer.withStamps(Stamps.NONE)).withTestClasspath(() -> {
                    try {
                        return dependencyResolution.testClasspath(project, session);
                    } catch (LifecycleExecutionException e) {
                        throw new NotCacheableException("cannot resolve the test classpath: " + e.getMessage(), e);
                    }
                });

        CacheKey key = null;
        if (cache != null) {
            key = cacheKey(m, session, project, ctx, descriptor, label);
            if (key != null && config.read()) {
                Optional<CacheEntry> entry = cache.lookup(key.hash());
                if (entry.isPresent() && entry.get().isRedirect()) {
                    entry = cache.lookup(CacheKeyCalculator.pinned(key.hash(), normalizer.stamps()));
                }
                if (entry.isPresent() && restore(entry.get(), ctx, normalizer, cache, label, key.hash())) {
                    if (config.state()) {
                        StateFiles.record(project, m, entry.get().state(), null, UpstreamState.phaseOf(m, project, lifecycle(session)));
                    }
                    return;
                }
            }
        }

        // System properties are JVM-wide: with modules building in parallel (-T, mvnd) another module's
        // change would be attributed to this mojo, so they are only tracked in sequential builds.
        boolean trackSystemProperties = !session.isParallel();
        ProjectState before = ProjectState.capture(project, trackSystemProperties);
        runner.run(m);
        List<CacheEntry.StateRecord> state = null;
        String incomplete = null;
        try {
            state = before.diff(ProjectState.capture(project, trackSystemProperties), normalizer);
            state = withReplayedEffects(session, project, m, state, normalizer);
        } catch (NotCacheableException e) {
            incomplete = e.getMessage();
        }
        if (config.state()) {
            StateFiles.record(project, m, state == null ? List.of() : state, incomplete,
                    UpstreamState.phaseOf(m, project, lifecycle(session)));
        }
        if (key != null) {
            store(m, ctx, normalizer.stamps(), cache, config, descriptor.get(), key, state, incomplete, label);
        }
    }

    /**
     * If this execution's recorded state was replayed earlier in this build (it was not in the plan seen
     * then), the diff around its run misses those changes: they were already there. Records that still
     * hold are its own effects and are added back.
     */
    private List<CacheEntry.StateRecord> withReplayedEffects(MavenSession session, MavenProject project, MojoExecution m,
            List<CacheEntry.StateRecord> diff, PathNormalizer normalizer) {
        Map<String, Map<String, List<CacheEntry.StateRecord>>> projects = replayedState.get(session);
        Map<String, List<CacheEntry.StateRecord>> applied = projects == null ? null
                : projects.get(project.getGroupId() + ":" + project.getArtifactId());
        List<CacheEntry.StateRecord> earlier = applied == null ? null
                : applied.remove(StateFiles.identity(StateFiles.executionId(m)));
        if (earlier == null || earlier.isEmpty()) {
            return diff;
        }
        List<CacheEntry.StateRecord> merged = new ArrayList<>(diff);
        for (CacheEntry.StateRecord r : earlier) {
            boolean covered = diff.stream().anyMatch(d -> d.kind().equals(r.kind()) && Objects.equals(d.name(), r.name()));
            if (!covered && ProjectState.holds(r, project, normalizer)) {
                merged.add(r);
            }
        }
        return merged;
    }

    /** The cache key, or {@code null} (with a log line) when the execution cannot be cached. */
    private CacheKey cacheKey(MojoExecution m, MavenSession session, MavenProject project, ExecutionContext ctx,
                              Optional<MojoCacheDescriptor> descriptor, String label) {
        if (descriptor.isEmpty()) {
            LOG.debug(PREFIX + "no declaration for {}, running", label);
            return null;
        }
        String reason = structuralReason(m, descriptor.get());
        if (reason != null) {
            LOG.info(PREFIX + "SKIP  {}: {}", label, reason);
            return null;
        }
        // Resolving for the key must not change what Maven resolved for the build: see Saved.
        DependencyResolution.Saved dependencies = DependencyResolution.save(project);
        try {
            dependencyResolution.resolveFor(m, project, session);
            for (MojoExecution fork : ForkedGoals.of(m)) {
                dependencyResolution.resolveFor(fork, project, session);
            }
            CacheKey key = CacheKeyCalculator.compute(ctx, descriptor.get());
            writeKeyManifest(project, m, key, descriptor.get());
            return key;
        } catch (NotCacheableException e) {
            LOG.info(PREFIX + "SKIP  {}: {}", label, e.getMessage());
        } catch (LifecycleExecutionException | RuntimeException e) {
            LOG.warn(PREFIX + "SKIP  {}: cannot compute cache key ({})", label, e.toString());
            LOG.debug(PREFIX + "key failure", e);
        } finally {
            dependencies.restore();
        }
        return null;
    }

    private void store(MojoExecution m, ExecutionContext ctx, Stamps stamps, LocalCache cache, MojoIoConfig config,
                       MojoCacheDescriptor descriptor, CacheKey key, List<CacheEntry.StateRecord> state,
                       String incomplete, String label) {
        String hash = key.hash();
        if (!config.write()) {
            LOG.info(PREFIX + "MISS  {} [{}] (cache writes disabled)", label, shortHash(hash));
            return;
        }
        if (incomplete != null) {
            LOG.info(PREFIX + "MISS  {} [{}] not stored: {}", label, shortHash(hash), incomplete);
            return;
        }
        try {
            Map<String, Path> outputs = new TreeMap<>();
            List<LocalCache.StoredFile> files = new ArrayList<>();
            String unstampable = null;
            for (OutputSpec spec : descriptor.outputs()) {
                for (ResolvedOutput output : spec.resolve(ctx)) {
                    for (Path file : output.existingFiles()) {
                        Path abs = file.toAbsolutePath().normalize();
                        if (!abs.startsWith(ctx.basedir())) {
                            throw new NotCacheableException("output " + abs + " is outside the project directory");
                        }
                        outputs.put(FileTrees.relative(ctx.basedir(), abs), abs);
                    }
                }
            }
            for (Map.Entry<String, Path> o : outputs.entrySet()) {
                Restamping.Mode mode = descriptor.stampingCarry() ? Restamping.classify(o.getValue(), stamps)
                        : Restamping.Mode.RAW;
                if (mode == Restamping.Mode.UNSTAMPABLE && unstampable == null) {
                    unstampable = o.getKey();
                }
                files.add(new LocalCache.StoredFile(stamps.tokenize(o.getKey()), o.getValue(), mode));
            }
            String storeKey = hash;
            if (unstampable != null) {
                // The stamp-free key cannot serve other stamp values: point it at a key pinned to these.
                cache.storeRedirect(hash, CacheKeyCalculator.mojoId(m),
                        unstampable + " embeds stamp values where they cannot be rewritten");
                storeKey = CacheKeyCalculator.pinned(hash, stamps);
            }
            cache.store(storeKey, CacheKeyCalculator.mojoId(m), files, state, stamps.values(), key.manifest());
            LOG.info(PREFIX + "MISS  {} [{}] stored {} file(s), {} state change(s){}", label, shortHash(hash),
                    outputs.size(), state.size(), unstampable == null ? "" : ", pinned to stamp values ("
                            + unstampable + " embeds them)");
        } catch (NotCacheableException e) {
            LOG.info(PREFIX + "MISS  {} [{}] not stored: {}", label, shortHash(hash), e.getMessage());
        } catch (RuntimeException e) {
            LOG.warn(PREFIX + "MISS  {} [{}] not stored: {}", label, shortHash(hash), e.toString());
            LOG.debug(PREFIX + "store failure", e);
        }
    }

    private boolean restore(CacheEntry entry, ExecutionContext ctx, PathNormalizer normalizer, LocalCache cache,
                            String label, String hash) {
        Set<Path> missing = new LinkedHashSet<>();
        for (CacheEntry.StateRecord r : entry.state()) {
            missing.addAll(normalizer.missingRepositoryFiles(r.value()));
        }
        if (!missing.isEmpty()) {
            LOG.info(PREFIX + "MISS  {} [{}] cached state needs files missing from the local repository: {}",
                    label, shortHash(hash), missing);
            return false;
        }
        try {
            cache.restoreFiles(entry, ctx.basedir(), normalizer.stamps());
            ProjectState.apply(entry.state(), ctx.project(), normalizer, projectHelper);
        } catch (RuntimeException e) {
            LOG.warn(PREFIX + "restore of {} failed, running instead: {}", label, e.toString());
            return false;
        }
        boolean carriesStamps = entry.files().stream().anyMatch(f -> f.mode() == Restamping.Mode.TEXT
                || f.mode() == Restamping.Mode.ARCHIVE || f.path().contains("@@STAMP:"))
                || entry.state().stream().anyMatch(r -> r.value() != null && r.value().contains("@@STAMP:"));
        boolean restamped = carriesStamps && !entry.stamps().equals(normalizer.stamps().values());
        LOG.info(PREFIX + "HIT   {} [{}] restored {} file(s), {} state change(s){}", label, shortHash(hash),
                entry.files().size(), entry.state().size(), restamped ? ", restamped " + entry.stamps()
                        + " -> " + normalizer.stamps().values() : "");
        return true;
    }

    /** Reasons that need no key computation. */
    private static String structuralReason(MojoExecution m, MojoCacheDescriptor descriptor) {
        if (!descriptor.cacheable()) {
            return descriptor.notCacheableReason();
        }
        if (m.getMojoDescriptor() != null && m.getMojoDescriptor().isAggregator() && !descriptor.reactorWide()) {
            return "aggregator mojos are only cached with a reactorModels input";
        }
        return null;
    }

    static PathNormalizer normalizer(MavenSession session, MavenProject project) {
        Path root = session.getRequest().getMultiModuleProjectDirectory() != null
                ? session.getRequest().getMultiModuleProjectDirectory().toPath()
                : session.getTopLevelProject().getBasedir().toPath();
        Path repo = Path.of(session.getRepositorySession().getLocalRepository().getBasedir().getAbsolutePath());
        return new PathNormalizer(project.getBasedir().toPath(), root, repo);
    }

    /** Writes the key's inputs to target/frontseat/ so a miss can be explained by diffing two runs. */
    private static void writeKeyManifest(MavenProject project, MojoExecution m, CacheKey key,
                                         MojoCacheDescriptor descriptor) {
        try {
            Path dir = Path.of(project.getBuild().getDirectory(), "mojo-io");
            Files.createDirectories(dir);
            String name = m.getArtifactId() + "-" + m.getGoal() + "-" + m.getExecutionId() + ".key.txt";
            Files.writeString(dir.resolve(name), "key = " + key.hash() + "\ndescriptor = " + descriptor.source() + "\n"
                    + key.manifest(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            LOG.debug(PREFIX + "cannot write key manifest", e);
        }
    }

    private static String label(MojoExecution m, MavenProject project) {
        String prefix = m.getMojoDescriptor() != null && m.getMojoDescriptor().getPluginDescriptor() != null
                ? m.getMojoDescriptor().getPluginDescriptor().getGoalPrefix()
                : m.getArtifactId();
        return prefix + ":" + m.getGoal() + " (" + m.getExecutionId() + ") @ " + project.getArtifactId();
    }

    private static String shortHash(String hash) {
        return hash.substring(0, 12);
    }
}
