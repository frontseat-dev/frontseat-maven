package dev.frontseat.mojoio.extension;

import dev.frontseat.mojoio.cache.CacheEntry;
import dev.frontseat.mojoio.cache.CacheKey;
import dev.frontseat.mojoio.cache.LocalCache;
import dev.frontseat.mojoio.extension.descriptor.DescriptorRegistry;
import dev.frontseat.mojoio.extension.descriptor.MojoCacheDescriptor;
import dev.frontseat.mojoio.extension.descriptor.OutputSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
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

    @Inject
    public MojoIoExecutionStrategy(DescriptorRegistry registry, DependencyResolution dependencyResolution,
                                           PrerequisiteExpander prerequisites) {
        this.registry = registry;
        this.dependencyResolution = dependencyResolution;
        this.prerequisites = prerequisites;
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
        List<MojoExecution> plan = config.prerequisites()
                ? prerequisites.expand(executions, session, project)
                : executions;
        LocalCache cache = new LocalCache(config.cacheDir());
        for (MojoExecution m : plan) {
            executeOne(m, session, project, runner, cache, config);
        }
    }

    private void executeOne(MojoExecution m, MavenSession session, MavenProject project, MojoExecutionRunner runner,
                            LocalCache cache, MojoIoConfig config) throws LifecycleExecutionException {
        String label = label(m, project);
        Optional<MojoCacheDescriptor> found = registry.find(m, session);
        if (found.isEmpty()) {
            LOG.debug(PREFIX + "no descriptor for {}, running", label);
            runner.run(m);
            return;
        }
        MojoCacheDescriptor descriptor = found.get();
        String reason = structuralReason(m, descriptor);
        if (reason != null) {
            LOG.info(PREFIX + "SKIP  {}: {}", label, reason);
            runner.run(m);
            return;
        }

        PathNormalizer normalizer = normalizer(session, project);
        ExecutionContext ctx = new ExecutionContext(session, project, m, normalizer);
        CacheKey key;
        try {
            dependencyResolution.resolveFor(m, project, session);
            key = CacheKeyCalculator.compute(ctx, descriptor);
        } catch (NotCacheableException e) {
            LOG.info(PREFIX + "SKIP  {}: {}", label, e.getMessage());
            runner.run(m);
            return;
        } catch (RuntimeException e) {
            LOG.warn(PREFIX + "SKIP  {}: cannot compute cache key ({})", label, e.toString());
            LOG.debug(PREFIX + "key failure", e);
            runner.run(m);
            return;
        }
        String hash = key.hash();
        writeKeyManifest(project, m, key, descriptor);

        if (config.read()) {
            Optional<CacheEntry> entry = cache.lookup(hash);
            if (entry.isPresent() && restore(entry.get(), ctx, cache, label, hash)) {
                return;
            }
        }

        ProjectState before = ProjectState.capture(project);
        runner.run(m);
        if (!config.write()) {
            LOG.info(PREFIX + "MISS  {} [{}] (cache writes disabled)", label, shortHash(hash));
            return;
        }
        try {
            List<CacheEntry.StateRecord> state = before.diff(ProjectState.capture(project), normalizer);
            Map<String, Path> outputs = new TreeMap<>();
            for (OutputSpec output : descriptor.outputs()) {
                output.collect(ctx, outputs);
            }
            cache.store(hash, CacheKeyCalculator.mojoId(m), outputs, state, key.manifest());
            LOG.info(PREFIX + "MISS  {} [{}] stored {} file(s), {} state change(s)", label, shortHash(hash),
                    outputs.size(), state.size());
        } catch (NotCacheableException e) {
            LOG.info(PREFIX + "MISS  {} [{}] not stored: {}", label, shortHash(hash), e.getMessage());
        } catch (RuntimeException e) {
            LOG.warn(PREFIX + "MISS  {} [{}] not stored: {}", label, shortHash(hash), e.toString());
            LOG.debug(PREFIX + "store failure", e);
        }
    }

    private boolean restore(CacheEntry entry, ExecutionContext ctx, LocalCache cache, String label, String hash) {
        Set<Path> missing = new LinkedHashSet<>();
        for (CacheEntry.StateRecord r : entry.state()) {
            missing.addAll(ctx.normalizer().missingRepositoryFiles(r.value()));
        }
        if (!missing.isEmpty()) {
            LOG.info(PREFIX + "MISS  {} [{}] cached state needs files missing from the local repository: {}",
                    label, shortHash(hash), missing);
            return false;
        }
        try {
            cache.restoreFiles(entry, ctx.basedir());
            ProjectState.apply(entry.state(), ctx.project(), ctx.normalizer());
        } catch (RuntimeException e) {
            LOG.warn(PREFIX + "restore of {} failed, running instead: {}", label, e.toString());
            return false;
        }
        LOG.info(PREFIX + "HIT   {} [{}] restored {} file(s), {} state change(s)", label, shortHash(hash),
                entry.files().size(), entry.state().size());
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

    private static PathNormalizer normalizer(MavenSession session, MavenProject project) {
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
