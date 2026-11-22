package dev.frontseat.mojoio.extension.descriptor;

import dev.frontseat.mojoio.cache.AntPattern;
import dev.frontseat.mojoio.cache.FileTrees;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    /** Whether {@code file} falls under this output's path and patterns. */
    public boolean covers(Path file) {
        Path p = path.toAbsolutePath().normalize();
        Path f = file.toAbsolutePath().normalize();
        if (f.equals(p)) {
            return true;
        }
        return f.startsWith(p) && AntPattern.selected(FileTrees.relative(p, f), includes, excludes);
    }

    /**
     * The files this output covers right now. Empty for an observed output: what it covers is known
     * only from what the mojo wrote (see WriteSnapshot).
     */
    public List<Path> existingFiles() {
        if (observed) {
            return List.of();
        }
        if (Files.isRegularFile(path)) {
            return List.of(path);
        }
        List<Path> files = new ArrayList<>();
        for (Path f : FileTrees.listFiles(path)) {
            if (AntPattern.selected(FileTrees.relative(path, f), includes, excludes)) {
                files.add(f);
            }
        }
        return files;
    }
}
