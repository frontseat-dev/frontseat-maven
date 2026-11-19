package dev.frontseat.mojoio.extension;

import dev.frontseat.mojoio.cache.CacheKey;
import dev.frontseat.mojoio.extension.descriptor.InputSpec;
import dev.frontseat.mojoio.extension.descriptor.MojoCacheDescriptor;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecution;

/**
 * Key for one execution = plugin coordinates (+ plugin dependencies) + goal + JDK + effective
 * configuration + declared inputs.
 */
final class CacheKeyCalculator {

    private CacheKeyCalculator() {}

    static CacheKey compute(ExecutionContext ctx, MojoCacheDescriptor descriptor) throws NotCacheableException {
        MojoExecution m = ctx.execution();
        CacheKey key = new CacheKey();
        key.add("mojo", mojoId(m));
        key.add("pluginDependencies", pluginDependencies(m.getPlugin()));
        key.add("jdk", System.getProperty("java.vendor") + " " + System.getProperty("java.runtime.version"));
        key.add("configuration", ConfigurationHasher.render(ctx, descriptor.ignoredParameters()));
        for (InputSpec input : descriptor.inputs()) {
            input.contribute(ctx, key);
        }
        return key;
    }

    static String mojoId(MojoExecution m) {
        return m.getGroupId() + ":" + m.getArtifactId() + ":" + m.getVersion() + ":" + m.getGoal();
    }

    private static String pluginDependencies(Plugin plugin) {
        if (plugin == null || plugin.getDependencies() == null) {
            return "";
        }
        List<String> deps = new ArrayList<>();
        for (Dependency d : plugin.getDependencies()) {
            deps.add(d.getGroupId() + ":" + d.getArtifactId() + ":" + d.getType() + ":" + d.getClassifier() + ":"
                    + d.getVersion());
        }
        return String.join(",", deps);
    }
}
