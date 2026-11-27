package dev.frontseat.mojoio.extension;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import org.apache.maven.execution.MavenSession;

/**
 * Settings, read from {@code -D} user properties first, then system properties.
 *
 * <ul>
 *   <li>{@code mojoio.enabled} (default {@code true}): master switch.</li>
 *   <li>{@code mojoio.state.enabled} (default {@code true}): record each execution's project-state
 *       changes in {@code target/mojo-io/state/} and replay them for executions that do not run.</li>
 *   <li>{@code mojoio.graph.output} (default unset): write the task graph to this file instead of
 *       running mojos (dry run).</li>
 *   <li>{@code mojoio.graph.granularity} ({@code phase}, {@code stage} (default), {@code module}): how the
 *       graph groups a project's executions into tasks. {@code phase}: one task per lifecycle phase (with its
 *       hooks). {@code stage}: compile, test, package, verify. {@code module}: one task per project. Fewer,
 *       bigger tasks mean fewer Maven invocations for a build system; smaller ones mean finer caching.</li>
 *   <li>{@code mojoio.only} (default unset): comma-separated project ids ({@code groupId:artifactId} or
 *       {@code artifactId}). Other projects in the reactor replay their state but run no mojos, so
 *       {@code -pl app -am -Dmojoio.only=app} builds only {@code app} against its upstream modules'
 *       outputs (how a build system runs one task).</li>
 * </ul>
 */
public record MojoIoConfig(boolean enabled, boolean state, Path graphOutput, java.util.Set<String> only,
                           Granularity granularity) {

    /** How the task graph groups a project's executions into tasks. */
    public enum Granularity { PHASE, STAGE, MODULE }

    /** Whether {@code project}'s mojos should run (see {@code mojoio.only}). */
    public boolean runs(org.apache.maven.project.MavenProject project) {
        return only.isEmpty() || only.contains(project.getArtifactId())
                || only.contains(project.getGroupId() + ":" + project.getArtifactId());
    }

    public static MojoIoConfig from(MavenSession session) {
        Properties user = session.getUserProperties();
        Properties system = session.getSystemProperties();
        String graph = get(user, system, "mojoio.graph.output", null);
        Path base = session.getRequest().getBaseDirectory() == null ? Paths.get("")
                : Paths.get(session.getRequest().getBaseDirectory());
        return new MojoIoConfig(
                bool(user, system, "mojoio.enabled", true),
                bool(user, system, "mojoio.state.enabled", true),
                graph == null ? null : base.resolve(graph).toAbsolutePath().normalize(),
                java.util.Set.of(get(user, system, "mojoio.only", "").split("\\s*,\\s*")).stream()
                        .filter(x -> !x.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet()),
                Granularity.valueOf(get(user, system, "mojoio.graph.granularity", "stage").toUpperCase(java.util.Locale.ROOT)));
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
