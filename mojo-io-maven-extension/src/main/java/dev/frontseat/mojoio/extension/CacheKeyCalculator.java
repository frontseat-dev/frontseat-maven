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
        // A plugin or plugin dependency built in this reactor (a build-tools module): its coordinates
        // say nothing about its content.
        for (String id : TaskGraphCollector.pluginReactorDependencies(m, ctx.session())) {
            for (org.apache.maven.project.MavenProject p : ctx.session().getAllProjects()) {
                if (!(p.getGroupId() + ":" + p.getArtifactId()).equals(id)) {
                    continue;
                }
                java.io.File artifact = p.getArtifact() == null ? null : p.getArtifact().getFile();
                Path content = artifact != null && artifact.exists() ? artifact.toPath()
                        : Path.of(p.getBuild().getOutputDirectory());
                key.add("pluginReactorDependency." + id, FileTrees.hashClasspathEntry(content, null));
            }
        }
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
        dev.frontseat.mojoio.cache.ContentFilter filter = ctx.normalizer().stamps().isEmpty() ? null
                : ctx.normalizer().stamps().contentFilter();
        return switch (input.kind()) {
            case VALUE -> input.value() == null ? "null" : input.value();
            case CLASSPATH -> {
                List<String> parts = new ArrayList<>();
                for (Path p : input.paths()) {
                    String h = FileTrees.hashClasspathEntry(p, filter);
                    // An empty directory and a missing one are the same classpath entry: nothing.
                    parts.add(h.equals(FileTrees.ABSENT) ? h : (Files.isDirectory(p) ? "dir:" : "file:") + h);
                }
                yield String.join(",", parts);
            }
            case FILES -> {
                List<String> parts = new ArrayList<>();
                for (Path p : input.paths()) {
                    String h = filesHash(p, input, filter);
                    if (h.equals(FileTrees.ABSENT)) {
                        // A path with no content contributes nothing: whether a source root that does not
                        // exist yet is listed depends on mojo order (e.g. replayed state), not on content.
                        continue;
                    }
                    parts.add(ctx.normalizer().normalize(p.toString()) + " " + h);
                }
                yield String.join(";", parts);
            }
        };
    }

    /** Key for an entry whose outputs embed stamp values that cannot be rewritten: pinned to the values. */
    static String pinned(String key, dev.frontseat.mojoio.cache.Stamps stamps) {
        return Hashing.sha256(key + "|stamps=" + stamps.values());
    }

    private static String filesHash(Path p, ResolvedInput input, dev.frontseat.mojoio.cache.ContentFilter filter) {
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
        if (sb.length() == 0) {
            return FileTrees.ABSENT;
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

    static String pluginDependencies(Plugin plugin) {
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
