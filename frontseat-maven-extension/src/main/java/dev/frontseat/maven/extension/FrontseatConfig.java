package dev.frontseat.maven.extension;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import org.apache.maven.execution.MavenSession;

/**
 * Settings, read from {@code -D} user properties first, then system properties.
 *
 * <ul>
 *   <li>{@code frontseat.cache.enabled} (default {@code true}): master switch.</li>
 *   <li>{@code frontseat.cache.dir} (default {@code ~/.m2/frontseat-cache}): local cache location.</li>
 *   <li>{@code frontseat.cache.read} / {@code frontseat.cache.write} (default {@code true}).</li>
 *   <li>{@code frontseat.prerequisites} (default {@code true}): when a goal such as
 *       {@code surefire:test} is invoked directly, first run (or restore) the lifecycle it depends on.</li>
 * </ul>
 */
public record FrontseatConfig(boolean enabled, Path cacheDir, boolean read, boolean write, boolean prerequisites) {

    public static FrontseatConfig from(MavenSession session) {
        Properties user = session.getUserProperties();
        Properties system = session.getSystemProperties();
        String dir = get(user, system, "frontseat.cache.dir",
                Paths.get(System.getProperty("user.home"), ".m2", "frontseat-cache").toString());
        return new FrontseatConfig(
                bool(user, system, "frontseat.cache.enabled", true),
                Paths.get(dir).toAbsolutePath().normalize(),
                bool(user, system, "frontseat.cache.read", true),
                bool(user, system, "frontseat.cache.write", true),
                bool(user, system, "frontseat.prerequisites", true));
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
