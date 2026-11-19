package dev.frontseat.mojoio.extension;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import org.apache.maven.execution.MavenSession;

/**
 * Settings, read from {@code -D} user properties first, then system properties.
 *
 * <ul>
 *   <li>{@code mojoio.cache.enabled} (default {@code true}): master switch.</li>
 *   <li>{@code mojoio.cache.dir} (default {@code ~/.m2/mojo-io-cache}): local cache location.</li>
 *   <li>{@code mojoio.cache.read} / {@code mojoio.cache.write} (default {@code true}).</li>
 *   <li>{@code mojoio.prerequisites} (default {@code true}): when a goal such as
 *       {@code surefire:test} is invoked directly, first run (or restore) the lifecycle it depends on.</li>
 * </ul>
 */
public record MojoIoConfig(boolean enabled, Path cacheDir, boolean read, boolean write, boolean prerequisites) {

    public static MojoIoConfig from(MavenSession session) {
        Properties user = session.getUserProperties();
        Properties system = session.getSystemProperties();
        String dir = get(user, system, "mojoio.cache.dir",
                Paths.get(System.getProperty("user.home"), ".m2", "mojo-io-cache").toString());
        return new MojoIoConfig(
                bool(user, system, "mojoio.cache.enabled", true),
                Paths.get(dir).toAbsolutePath().normalize(),
                bool(user, system, "mojoio.cache.read", true),
                bool(user, system, "mojoio.cache.write", true),
                bool(user, system, "mojoio.prerequisites", true));
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
