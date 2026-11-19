package dev.frontseat.mojoio.cache;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
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

    /**
     * Like {@link #hash(Path)}, but a jar/zip is hashed by its entries (name and content) instead of
     * its bytes. Archive bytes include per-entry timestamps, so a jar re-packed from identical classes
     * (every {@code mvn package} without {@code project.build.outputTimestamp}) would otherwise hash
     * differently each build. Entry order and compression are ignored too.
     */
    public static String hashClasspathEntry(Path path) {
        if (Files.isRegularFile(path) && isArchive(path)) {
            try (ZipFile zip = new ZipFile(path.toFile())) {
                List<? extends ZipEntry> entries = new ArrayList<>(Collections.list(zip.entries()));
                entries.sort(Comparator.comparing(ZipEntry::getName));
                StringBuilder manifest = new StringBuilder("archive\n");
                for (ZipEntry e : entries) {
                    if (e.isDirectory()) {
                        continue;
                    }
                    MessageDigest digest = Hashing.newDigest();
                    try (InputStream in = zip.getInputStream(e)) {
                        byte[] buffer = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buffer)) > 0) {
                            digest.update(buffer, 0, n);
                        }
                    }
                    manifest.append(e.getName()).append('\0').append(HexFormat.of().formatHex(digest.digest()))
                            .append('\n');
                }
                return Hashing.sha256(manifest.toString());
            } catch (IOException e) {
                return Hashing.sha256(path); // not a readable archive after all: fall back to bytes
            }
        }
        return hash(path);
    }

    private static boolean isArchive(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".jar") || name.endsWith(".zip") || name.endsWith(".war");
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
