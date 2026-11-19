package dev.frontseat.maven.pluginio;

import java.util.List;
import java.util.Optional;

/**
 * I/O declarations for the goals of one plugin. Coordinates may be omitted when the document ships
 * inside the plugin's own jar (they are implied); they are required anywhere else.
 */
public record PluginIo(String groupId, String artifactId, List<MojoIo> mojos) {

    public PluginIo {
        mojos = List.copyOf(mojos);
    }

    public Optional<MojoIo> mojo(String goal) {
        return mojos.stream().filter(m -> m.goal().equals(goal)).findFirst();
    }

    /** {@code groupId:artifactId}, or {@code null} when the coordinates are implied. */
    public String key() {
        return groupId == null || artifactId == null ? null : groupId + ":" + artifactId;
    }

    public PluginIo withCoordinates(String groupId, String artifactId) {
        return new PluginIo(groupId, artifactId, mojos);
    }
}
