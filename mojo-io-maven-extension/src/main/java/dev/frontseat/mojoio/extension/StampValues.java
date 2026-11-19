package dev.frontseat.mojoio.extension;

import dev.frontseat.mojoio.cache.Stamps;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

/**
 * The stamp values of a project: its {@code project.version}, plus the properties named in
 * {@code -Dmojoio.stamp.properties=buildNumber,git.commit.id} (looked up in user properties, then
 * project properties). See docs/versioning.md.
 */
final class StampValues {

    private StampValues() {}

    static Stamps of(MavenSession session, MavenProject project) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("project.version", project.getVersion());
        String extra = session.getUserProperties().getProperty("mojoio.stamp.properties",
                session.getSystemProperties().getProperty("mojoio.stamp.properties", ""));
        for (String name : extra.split("[,\\s]+")) {
            if (name.isBlank()) {
                continue;
            }
            String v = session.getUserProperties().getProperty(name, project.getProperties().getProperty(name));
            if (v != null) {
                values.put(name, v);
            }
        }
        return new Stamps(values);
    }
}
