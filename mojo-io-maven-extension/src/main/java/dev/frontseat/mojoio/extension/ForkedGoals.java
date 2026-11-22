package dev.frontseat.mojoio.extension;

import dev.frontseat.mojoio.extension.descriptor.InputSpec;
import dev.frontseat.mojoio.extension.descriptor.MojoCacheDescriptor;
import dev.frontseat.mojoio.extension.descriptor.DescriptorRegistry;
import dev.frontseat.mojoio.extension.descriptor.OutputSpec;
import dev.frontseat.mojoio.extension.descriptor.ResolvedInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;

/**
 * A mojo that forks other executions first (e.g. {@code spotbugs:check} forks {@code spotbugs:spotbugs},
 * {@code pmd:check} forks {@code pmd:pmd}) runs them inside its own run, so they are cached as one unit:
 * the forked executions' configuration and inputs join the key, their outputs join the entry. That needs
 * a cacheable declaration for every forked execution, all in the mojo's own project.
 */
final class ForkedGoals {

    private ForkedGoals() {}

    /** Forked executions of {@code m} in {@code project}; empty when it forks nothing. */
    static List<MojoExecution> of(MojoExecution m) {
        List<MojoExecution> all = new ArrayList<>();
        if (m.getForkedExecutions() != null) {
            m.getForkedExecutions().values().forEach(all::addAll);
        }
        return all;
    }

    /**
     * The widest dependency scope Maven resolves for {@code m}: its own or that of an execution it forks
     * (which runs inside it). {@code null} when none needs resolution.
     */
    static String resolution(MojoExecution m) {
        String widest = scope(m);
        for (MojoExecution fork : of(m)) {
            String s = scope(fork);
            if (s != null && (widest == null
                    || LifecycleModel.resolutionScopes(s).size() > LifecycleModel.resolutionScopes(widest).size())) {
                widest = s;
            }
        }
        return widest;
    }

    private static String scope(MojoExecution m) {
        String s = m.getMojoDescriptor() == null ? null : m.getMojoDescriptor().getDependencyResolutionRequired();
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * {@code own} extended with what {@code m}'s forked executions read and write, or marked not
     * cacheable when one of them cannot be part of the unit.
     */
    static MojoCacheDescriptor merge(MojoExecution m, MavenProject project, MavenSession session,
                                     DescriptorRegistry registry, MojoCacheDescriptor own) {
        Map<String, List<MojoExecution>> forks = m.getForkedExecutions();
        if (forks == null || forks.isEmpty() || !own.cacheable()) {
            return own;
        }
        String projectKey = project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion();
        List<InputSpec> inputs = new ArrayList<>();
        List<OutputSpec> outputs = new ArrayList<>();
        List<String> ignoredWrites = new ArrayList<>();
        for (Map.Entry<String, List<MojoExecution>> e : forks.entrySet()) {
            if (!e.getKey().equals(projectKey) && !e.getValue().isEmpty()) {
                return own.notCacheable("forks executions in another project (" + e.getKey() + ")");
            }
            for (MojoExecution fork : e.getValue()) {
                String id = fork.getArtifactId() + ":" + fork.getGoal();
                if (fork.getForkedExecutions() != null && !fork.getForkedExecutions().isEmpty()) {
                    return own.notCacheable("forked execution " + id + " forks again");
                }
                if (fork.getMojoDescriptor() != null && fork.getMojoDescriptor().isAggregator()) {
                    return own.notCacheable("forked execution " + id + " is an aggregator");
                }
                Optional<MojoCacheDescriptor> d = registry.find(fork, session);
                if (d.isEmpty()) {
                    return own.notCacheable("forked execution " + id + " has no plugin-io declaration");
                }
                if (!d.get().cacheable()) {
                    return own.notCacheable("forked execution " + id + ": " + d.get().notCacheableReason());
                }
                MojoCacheDescriptor forked = d.get();
                String prefix = "fork(" + StateFiles.executionId(fork) + ").";
                inputs.add(ctx -> {
                    ExecutionContext forkCtx = forkContext(ctx, fork);
                    List<ResolvedInput> resolved = new ArrayList<>();
                    resolved.add(ResolvedInput.value(prefix + "configuration", CacheKeyCalculator.mojoId(fork) + "\n"
                            + CacheKeyCalculator.pluginDependencies(fork.getPlugin()) + "\n"
                            + ConfigurationHasher.render(forkCtx, forked.ignoredParameters())));
                    for (InputSpec spec : forked.inputs()) {
                        for (ResolvedInput in : spec.resolve(forkCtx)) {
                            resolved.add(new ResolvedInput(prefix + in.name(), in.kind(), in.paths(), in.includes(),
                                    in.excludes(), in.value(), in.scope()));
                        }
                    }
                    return resolved;
                });
                for (OutputSpec spec : forked.outputs()) {
                    outputs.add(ctx -> spec.resolve(forkContext(ctx, fork)));
                }
                ignoredWrites.addAll(forked.ignoredWrites());
            }
        }
        return own.plus(inputs, outputs, ignoredWrites);
    }

    private static ExecutionContext forkContext(ExecutionContext ctx, MojoExecution fork) {
        return new ExecutionContext(ctx.session(), ctx.project(), fork, ctx.normalizer(), ctx.analysisOnly())
                .withTestClasspath(ctx.testClasspathResolver());
    }
}
