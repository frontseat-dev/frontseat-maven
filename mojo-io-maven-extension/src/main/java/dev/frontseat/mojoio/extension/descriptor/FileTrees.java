package dev.frontseat.mojoio.extension.descriptor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Listing of directory trees, with paths relative to their root. */
final class FileTrees {

    private FileTrees() {}

    /** All regular files under {@code dir}, sorted by relative path. Empty if {@code dir} is missing. */
    static List<Path> listFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            List<Path> files = new ArrayList<>(stream.filter(Files::isRegularFile).toList());
            files.sort((a, b) -> relative(dir, a).compareTo(relative(dir, b)));
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + dir, e);
        }
    }

    /** {@code file} relative to {@code base}, always with '/' separators. */
    static String relative(Path base, Path file) {
        return base.relativize(file).toString().replace('\\', '/');
    }
}
