package dev.frontseat.mojoio.extension.descriptor;

import java.nio.file.Path;
import java.util.List;

/**
 * A declared output, resolved for one execution: a file, or the files under a directory selected by
 * {@code includes}/{@code excludes} (Ant patterns; empty includes = everything).
 */
public record ResolvedOutput(String name, Path path, List<String> includes, List<String> excludes,
                             boolean observed) {

    public ResolvedOutput {
        includes = List.copyOf(includes);
        excludes = List.copyOf(excludes);
    }

    public ResolvedOutput(String name, Path path, List<String> includes, List<String> excludes) {
        this(name, path, includes, excludes, false);
    }
}
