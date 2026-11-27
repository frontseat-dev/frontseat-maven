package dev.frontseat.mojoio.extension;

import dev.frontseat.mojoio.extension.descriptor.InputSpec;
import dev.frontseat.mojoio.extension.descriptor.MojoCacheDescriptor;
import dev.frontseat.mojoio.extension.descriptor.ResolvedInput;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecution;

/** The fingerprint of an execution in the task graph: what a build system cannot hash itself. */
final class Fingerprint {

    /** Changes when the fingerprint's layout changes. */
    private static final String FORMAT = "mojo-io-fingerprint-v1";

    private Fingerprint() {}

    /**
     * Plugin coordinates and dependencies, goal, JDK, the evaluated configuration (minus parameters covered
     * by file inputs) and value inputs. Files and classpaths are left to the build system, which hashes them
     * its own way. Stamp values such as the project version are kept as they are, so a version bump changes
     * the fingerprint of every execution whose configuration or value inputs contain it.
     */
    static String of(ExecutionContext ctx, MojoCacheDescriptor descriptor) throws NotCacheableException {
        MojoExecution m = ctx.execution();
        List<String[]> components = new ArrayList<>();
        components.add(new String[] {"format", FORMAT});
        components.add(new String[] {"mojo", mojoId(m)});
        components.add(new String[] {"pluginDependencies", pluginDependencies(m.getPlugin())});
        components.add(new String[] {"jdk", System.getProperty("java.vendor") + " " + System.getProperty("java.runtime.version")});
        components.add(new String[] {"configuration", ConfigurationHasher.render(ctx, descriptor.ignoredParameters())});
        for (InputSpec spec : descriptor.inputs()) {
            for (ResolvedInput input : spec.resolve(ctx)) {
                if (input.kind() == ResolvedInput.Kind.VALUE) {
                    components.add(new String[] {"input." + input.name(), input.value() == null ? "null" : input.value()});
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String[] c : components) {
            sb.append(c[0].length()).append(':').append(c[0]).append(c[1].length()).append(':').append(c[1]);
        }
        return Hashing.sha256(sb.toString());
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
