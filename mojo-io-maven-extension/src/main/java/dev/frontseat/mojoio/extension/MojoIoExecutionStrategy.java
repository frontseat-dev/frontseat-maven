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
    /** Projects whose recorded state was already replayed in this JVM's session(s). */
    private final Set<MavenProject> replayed = Collections.newSetFromMap(new WeakHashMap<>());

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
        if (config.graphOutput() != null) {
            graph.add(session, project, executions); // dry run: describe, run nothing
            return;
        }
        List<MojoExecution> plan = config.prerequisites()
                ? prerequisites.expand(executions, session, project)
                : executions;
        PathNormalizer normalizer = normalizer(session, project).withStamps(StampValues.of(session, project));
        if (!config.runs(project)) {
            // Only here so others can resolve it: give it its recorded state, run nothing.
            if (config.state() && replayed.add(project)) {
                StateFiles.replayPending(project, List.of(), normalizer, projectHelper);
            }
            LOG.info(PREFIX + "ONLY  {} not selected by mojoio.only: {} execution(s) not run", project.getArtifactId(),
                    plan.size());
            return;
        }
        if (config.state() && replayed.add(project)) {
            StateFiles.replayPending(project, plan, normalizer, projectHelper);
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
        Optional<MojoCacheDescriptor> descriptor = registry.find(m, session);
        // `normalizer` tokenizes stamps (used for paths and state). The key only does so for mojos that
        // merely carry stamp values; for the others the values stay in the key.
        boolean carry = descriptor.map(MojoCacheDescriptor::stampingCarry).orElse(false);
        ExecutionContext ctx = new ExecutionContext(session, project, m,
                carry ? normalizer : normalizer.withStamps(Stamps.NONE));

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
                        StateFiles.record(project, m, entry.get().state(), null);
                    }
                    return;
                }
            }
        }

        boolean strict = config.strict() != MojoIoConfig.Strict.OFF && descriptor.isPresent()
                && structuralReason(m, descriptor.get()) == null;
        WriteSnapshot snapshot = null;
        if (strict) {
            if (config.cleanOutputs()) {
                cleanDeclaredOutputs(ctx, descriptor.get(), label);
            }
            snapshot = WriteSnapshot.take(session, project);
        }
        ProjectState before = ProjectState.capture(project);
        runner.run(m);
        List<CacheEntry.StateRecord> state = null;
        String incomplete = null;
        try {
            state = before.diff(ProjectState.capture(project), normalizer);
        } catch (NotCacheableException e) {
            incomplete = e.getMessage();
        }
        if (config.state()) {
            StateFiles.record(project, m, state == null ? List.of() : state, incomplete);
        }
        String undeclared = strict ? checkWrites(m, ctx, descriptor.get(), snapshot, config, label) : null;
        if (key != null) {
            store(m, ctx, normalizer.stamps(), cache, config, descriptor.get(), key, state,
                    incomplete != null ? incomplete : undeclared, label);
        }
    }

    /**
     * Strict mode: files the mojo wrote outside its declared outputs mean a cache entry would be
     * incomplete. Returns a reason not to store (or {@code null}); in {@code fail} mode, fails the build.
     */
    private String checkWrites(MojoExecution m, ExecutionContext ctx, MojoCacheDescriptor descriptor,
                               WriteSnapshot snapshot, MojoIoConfig config, String label)
            throws LifecycleExecutionException {
        List<String> undeclared;
        try {
            List<ResolvedOutput> outputs = new ArrayList<>();
            for (OutputSpec spec : descriptor.outputs()) {
                outputs.addAll(spec.resolve(ctx));
            }
            WriteSnapshot after = WriteSnapshot.take(ctx.session(), ctx.project());
            Path buildDirectory = Path.of(ctx.project().getBuild().getDirectory()).toAbsolutePath().normalize();
            undeclared = WriteSnapshot.undeclared(snapshot.changedSince(after), outputs, buildDirectory,
                    descriptor.ignoredWrites(), snapshot.basedir());
        } catch (NotCacheableException | RuntimeException e) {
            LOG.debug(PREFIX + "cannot check writes of {}", label, e);
            return null;
        }
        if (undeclared.isEmpty()) {
            return null;
        }
        String files = (undeclared.size() > 10 ? undeclared.subList(0, 10) + " and " + (undeclared.size() - 10)
                + " more" : undeclared.toString());
        String message = label + " wrote " + undeclared.size() + " file(s) outside its declared outputs ("
                + descriptor.source() + "): " + files;
        if (config.strict() == MojoIoConfig.Strict.FAIL) {
            throw new LifecycleExecutionException("[mojo-io] UNDECLARED " + message
                    + ". Fix the plugin-io declaration, or run with -Dmojoio.strict=warn.", m, ctx.project());
        }
        LOG.warn(PREFIX + "UNDECLARED {}", message);
        return "undeclared writes";
    }

    /** Strict mode with cleanOutputs: no leftover from an earlier build can end up in a new entry. */
    private void cleanDeclaredOutputs(ExecutionContext ctx, MojoCacheDescriptor descriptor, String label) {
        int deleted = 0;
        try {
            for (OutputSpec spec : descriptor.outputs()) {
                for (ResolvedOutput output : spec.resolve(ctx)) {
                    for (Path f : output.existingFiles()) {
                        if (f.toAbsolutePath().normalize().startsWith(ctx.basedir())) {
                            java.nio.file.Files.deleteIfExists(f);
                            deleted++;
                        }
                    }
                }
            }
        } catch (NotCacheableException | java.io.IOException | RuntimeException e) {
            LOG.debug(PREFIX + "cannot clean outputs of {}", label, e);
        }
        if (deleted > 0) {
            LOG.debug(PREFIX + "cleaned {} declared output(s) of {}", deleted, label);
        }
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
        try {
            dependencyResolution.resolveFor(m, project, session);
            CacheKey key = CacheKeyCalculator.compute(ctx, descriptor.get());
            writeKeyManifest(project, m, key, descriptor.get());
            return key;
        } catch (NotCacheableException e) {
            LOG.info(PREFIX + "SKIP  {}: {}", label, e.getMessage());
        } catch (LifecycleExecutionException | RuntimeException e) {
            LOG.warn(PREFIX + "SKIP  {}: cannot compute cache key ({})", label, e.toString());
            LOG.debug(PREFIX + "key failure", e);
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
        if (m.getMojoDescriptor() != null && m.getMojoDescriptor().isAggregator()) {
            return "aggregator mojos are not cached";
        }
        if (m.getForkedExecutions() != null && !m.getForkedExecutions().isEmpty()) {
            return "mojos with forked executions are not cached";
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
