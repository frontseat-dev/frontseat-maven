package dev.frontseat.mojoio.cache;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Comparator;
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
        return hash(path, null);
    }

    /**
     * Like {@link #hash(Path)}, with each file's content passed through {@code filter} first (e.g.
     * {@link Stamps#tokenizeContent}); {@code null} hashes raw bytes.
     */
    public static String hash(Path path, ContentFilter filter) {
        if (Files.isRegularFile(path)) {
            return fileHash(path, filter);
        }
        if (!Files.isDirectory(path)) {
            return ABSENT;
        }
        List<Path> files = listFiles(path);
        if (files.isEmpty()) {
            // An empty directory is equivalent to a missing one: a restored build has no empty
            // directories (only files are cached), a fresh one often does (e.g. javac's annotations dir).
            return ABSENT;
        }
        StringBuilder manifest = new StringBuilder();
        for (Path file : files) {
            manifest.append(relative(path, file)).append('\0').append(fileHash(file, filter)).append('\n');
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
        return hashClasspathEntry(path, null);
    }

    /** {@link #hashClasspathEntry(Path)} with content filtering, also applied to archive entries. */
    public static String hashClasspathEntry(Path path, ContentFilter filter) {
        if (Files.isRegularFile(path) && isArchive(path)) {
            return memoized(path, "archive", filter, () -> archiveHash(path, filter));
        }
        return hash(path, filter);
    }

    private static String archiveHash(Path path, ContentFilter filter) {
        {
            try (ZipFile zip = new ZipFile(path.toFile())) {
                List<? extends ZipEntry> entries = new ArrayList<>(Collections.list(zip.entries()));
                entries.sort(Comparator.comparing(ZipEntry::getName));
                StringBuilder manifest = new StringBuilder("archive\n");
                for (ZipEntry e : entries) {
                    if (e.isDirectory()) {
                        continue;
                    }
                    byte[] content;
                    try (InputStream in = zip.getInputStream(e)) {
                        content = in.readAllBytes();
                    }
                    if (filter != null) {
                        content = filter.apply(content);
                    }
                    manifest.append(e.getName()).append('\0').append(Hashing.sha256(content)).append('\n');
                }
                return Hashing.sha256(manifest.toString());
            } catch (IOException e) {
                return Hashing.sha256(path); // not a readable archive after all: fall back to bytes
            }
        }
    }

    private static String fileHash(Path file, ContentFilter filter) {
        return memoized(file, "file", filter, () -> {
            if (filter == null) {
                return Hashing.sha256(file);
            }
            try {
                if (Files.size(file) > 16L * 1024 * 1024) {
                    return Hashing.sha256(file);
                }
                return Hashing.sha256(filter.apply(Files.readAllBytes(file)));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot hash " + file, e);
            }
        });
    }

    /**
     * Content hashes, memoized by path, size and modification time (and filter): a reactor hashes the
     * same upstream jars and class directories for every downstream module and mojo. Lives as long as
     * the JVM, so a Maven Daemon (mvnd) also reuses it across builds. A rewrite that keeps both size and
     * modification time would be missed; filesystems record modification times in nanoseconds.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> MEMO =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static String memoized(Path file, String kind, ContentFilter filter,
                                   java.util.function.Supplier<String> compute) {
        String key;
        try {
            java.nio.file.attribute.BasicFileAttributes attrs =
                    Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
            key = kind + '|' + (filter == null ? "raw" : filter.id()) + '|' + attrs.size() + '|'
                    + attrs.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS) + '|'
                    + file.toAbsolutePath().normalize();
        } catch (IOException e) {
            return compute.get();
        }
        String hash = MEMO.get(key);
        if (hash == null) {
            if (MEMO.size() > 500_000) {
                MEMO.clear();
            }
            hash = compute.get();
            MEMO.put(key, hash);
        }
        return hash;
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
