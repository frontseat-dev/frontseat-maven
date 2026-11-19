package dev.frontseat.maven.cache;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Hashing and listing of files and directory trees. */
public final class FileTrees {

    /** Hash recorded for an input path that does not exist. */
    public static final String ABSENT = "absent";

    private FileTrees() {}

    /**
     * Content hash of a file or a directory tree. For a directory the hash covers every regular file's
     * path relative to the directory (with '/' separators) and its content, so it is independent of
     * where the tree lives on disk and of timestamps.
     */
    public static String hash(Path path) {
        if (Files.isRegularFile(path)) {
            return Hashing.sha256(path);
        }
        if (!Files.isDirectory(path)) {
            return ABSENT;
        }
        StringBuilder manifest = new StringBuilder();
        for (Path file : listFiles(path)) {
            manifest.append(relative(path, file)).append('\0').append(Hashing.sha256(file)).append('\n');
        }
        return Hashing.sha256(manifest.toString());
    }

    /** All regular files under {@code dir}, sorted by relative path. Empty if {@code dir} is missing. */
    public static List<Path> listFiles(Path dir) {
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
    public static String relative(Path base, Path file) {
        return base.relativize(file).toString().replace('\\', '/');
    }
}
