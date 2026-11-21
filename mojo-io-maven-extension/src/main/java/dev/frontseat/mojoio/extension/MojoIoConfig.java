package dev.frontseat.mojoio.extension;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import org.apache.maven.execution.MavenSession;

/**
 * Settings, read from {@code -D} user properties first, then system properties. The three features are
 * independent, so a tool can use exactly the parts it needs.
 *
 * <ul>
 *   <li>{@code mojoio.enabled} (default {@code true}): master switch.</li>
 *   <li>{@code mojoio.state.enabled} (default {@code true}): record each execution's project-state
 *       changes in {@code target/mojo-io/state/} and replay them for executions that do not run.</li>
 *   <li>{@code mojoio.graph.output} (default unset): write the task graph to this file instead of
 *       running mojos (dry run).</li>
 *   <li>{@code mojoio.cache.enabled} (default {@code true}): the built-in local cache.</li>
 *   <li>{@code mojoio.cache.dir} (default {@code ~/.m2/mojo-io-cache}),
 *       {@code mojoio.cache.read} / {@code mojoio.cache.write} (default {@code true}).</li>
 *   <li>{@code mojoio.only} (default unset): comma-separated project ids ({@code groupId:artifactId} or
 *       {@code artifactId}). Other projects in the reactor replay their state but run no mojos, so
 *       {@code -pl app -am -Dmojoio.only=app} builds only {@code app} against its upstream modules'
 *       restored outputs (how an external build system runs one task).</li>
 *   <li>{@code mojoio.prerequisites} (default {@code true}): when a goal such as
 *       {@code surefire:test} is invoked directly, first run (or restore) the lifecycle it depends on.</li>
 * </ul>
 */
public record MojoIoConfig(boolean enabled, boolean state, Path graphOutput, boolean cache, Path cacheDir,
                           boolean read, boolean write, boolean prerequisites, java.util.Set<String> only) {

    /** Whether {@code project}'s mojos should run (see {@code mojoio.only}). */
    public boolean runs(org.apache.maven.project.MavenProject project) {
        return only.isEmpty() || only.contains(project.getArtifactId())
                || only.contains(project.getGroupId() + ":" + project.getArtifactId());
    }

    public static MojoIoConfig from(MavenSession session) {
        Properties user = session.getUserProperties();
        Properties system = session.getSystemProperties();
        String dir = get(user, system, "mojoio.cache.dir",
                Paths.get(System.getProperty("user.home"), ".m2", "mojo-io-cache").toString());
        String graph = get(user, system, "mojoio.graph.output", null);
        Path base = session.getRequest().getBaseDirectory() == null ? Paths.get("")
                : Paths.get(session.getRequest().getBaseDirectory());
        return new MojoIoConfig(
                bool(user, system, "mojoio.enabled", true),
                bool(user, system, "mojoio.state.enabled", true),
                graph == null ? null : base.resolve(graph).toAbsolutePath().normalize(),
                bool(user, system, "mojoio.cache.enabled", true),
                Paths.get(dir).toAbsolutePath().normalize(),
                bool(user, system, "mojoio.cache.read", true),
                bool(user, system, "mojoio.cache.write", true),
                bool(user, system, "mojoio.prerequisites", true),
                java.util.Set.of(get(user, system, "mojoio.only", "").split("\\s*,\\s*")).stream()
                        .filter(x -> !x.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    private static String get(Properties user, Properties system, String name, String def) {
        String v = user.getProperty(name);
        if (v == null) {
            v = system.getProperty(name);
        }
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static boolean bool(Properties user, Properties system, String name, boolean def) {
        return Boolean.parseBoolean(get(user, system, name, Boolean.toString(def)));
    }
}
