package dev.frontseat.mojoio.extension;

import dev.frontseat.mojoio.cache.AntPattern;
import dev.frontseat.mojoio.cache.CacheKey;
import dev.frontseat.mojoio.cache.FileTrees;
import dev.frontseat.mojoio.cache.Hashing;
import dev.frontseat.mojoio.extension.descriptor.InputSpec;
import dev.frontseat.mojoio.extension.descriptor.MojoCacheDescriptor;
import dev.frontseat.mojoio.extension.descriptor.ResolvedInput;
import java.nio.file.Files;
import java.nio.file.Path;
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
        key.add("stamping", descriptor.stampingCarry() ? "carry" : "exact");
        key.add("configuration", ConfigurationHasher.render(ctx, descriptor.ignoredParameters()));
        for (InputSpec spec : descriptor.inputs()) {
            for (ResolvedInput input : spec.resolve(ctx)) {
                key.add("input." + input.name(), hash(ctx, input));
            }
        }
        return key;
    }

    /** The cache's view of a resolved input: content, not timestamps or absolute locations. */
    static String hash(ExecutionContext ctx, ResolvedInput input) {
        java.util.function.UnaryOperator<byte[]> filter = ctx.normalizer().stamps().isEmpty() ? null
                : ctx.normalizer().stamps()::tokenizeContent;
        return switch (input.kind()) {
            case VALUE -> input.value() == null ? "null" : input.value();
            case CLASSPATH -> {
                List<String> parts = new ArrayList<>();
                for (Path p : input.paths()) {
                    parts.add((Files.isDirectory(p) ? "dir:" : "file:") + FileTrees.hashClasspathEntry(p, filter));
                }
                yield String.join(",", parts);
            }
            case FILES -> {
                List<String> parts = new ArrayList<>();
                for (Path p : input.paths()) {
                    parts.add(ctx.normalizer().normalize(p.toString()) + " " + filesHash(p, input, filter));
                }
                yield String.join(";", parts);
            }
        };
    }

    /** Key for an entry whose outputs embed stamp values that cannot be rewritten: pinned to the values. */
    static String pinned(String key, dev.frontseat.mojoio.cache.Stamps stamps) {
        return Hashing.sha256(key + "|stamps=" + stamps.values());
    }

    private static String filesHash(Path p, ResolvedInput input, java.util.function.UnaryOperator<byte[]> filter) {
        if (input.includes().isEmpty() && input.excludes().isEmpty()) {
            return FileTrees.hash(p, filter);
        }
        StringBuilder sb = new StringBuilder();
        for (Path f : FileTrees.listFiles(p)) {
            String rel = FileTrees.relative(p, f);
            if (AntPattern.selected(rel, input.includes(), input.excludes())) {
                sb.append(rel).append('=').append(FileTrees.hash(f, filter)).append(';');
            }
        }
        return Hashing.sha256("includes=" + input.includes() + " excludes=" + input.excludes() + " " + sb);
    }

    /**
     * What an external build system cannot hash itself: plugin coordinates and dependencies, goal, JDK,
     * the evaluated configuration (minus parameters covered by file inputs) and value inputs. Files and
     * classpaths are left to the build system, which hashes them its own way. Stamp values stay in
     * (the build system restores outputs byte for byte, so a version bump must change the fingerprint).
     */
    static String fingerprint(ExecutionContext ctx, MojoCacheDescriptor descriptor) throws NotCacheableException {
        MojoExecution m = ctx.execution();
        CacheKey key = new CacheKey();
        key.add("mojo", mojoId(m));
        key.add("pluginDependencies", pluginDependencies(m.getPlugin()));
        key.add("jdk", System.getProperty("java.vendor") + " " + System.getProperty("java.runtime.version"));
        key.add("configuration", ConfigurationHasher.render(ctx, descriptor.ignoredParameters()));
        for (InputSpec spec : descriptor.inputs()) {
            for (ResolvedInput input : spec.resolve(ctx)) {
                if (input.kind() == ResolvedInput.Kind.VALUE) {
                    key.add("input." + input.name(), input.value() == null ? "null" : input.value());
                }
            }
        }
        return key.hash();
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
