package dev.frontseat.maven.cache;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Local content-addressed cache.
 *
 * <pre>
 * &lt;root&gt;/cas/ab/abcdef...        file contents, named by SHA-256
 * &lt;root&gt;/entries/12/1234...      entry manifests, named by cache key
 * &lt;root&gt;/keys/12/1234....txt     key manifests (inputs that produced the key), for debugging
 * </pre>
 *
 * Writes go to a temp file and are moved into place, so concurrent builds never see half-written
 * blobs or entries. Blobs are written before the entry that references them.
 */
public final class LocalCache {

    private final Path root;

    public LocalCache(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public Optional<CacheEntry> lookup(String key) {
        Path entryFile = entryPath(key);
        if (!Files.isRegularFile(entryFile)) {
            return Optional.empty();
        }
        try {
            CacheEntry entry = CacheEntry.decode(Files.readString(entryFile, StandardCharsets.UTF_8));
            for (CacheEntry.OutputFile f : entry.files()) {
                if (!Files.isRegularFile(blobPath(f.hash()))) {
                    return Optional.empty(); // incomplete entry, treat as a miss
                }
            }
            return Optional.of(entry);
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Stores an entry. {@code files} maps each output path (relative to the project base directory)
     * to the file on disk to copy into the store.
     */
    public CacheEntry store(String key, String mojo, Map<String, Path> files, List<CacheEntry.StateRecord> state,
                            String keyManifest) {
        List<CacheEntry.OutputFile> outputs = new ArrayList<>();
        try {
            for (Map.Entry<String, Path> e : files.entrySet()) {
                Path source = e.getValue();
                String hash = Hashing.sha256(source);
                Path blob = blobPath(hash);
                if (!Files.exists(blob)) {
                    Files.createDirectories(blob.getParent());
                    Path tmp = blob.resolveSibling(blob.getFileName() + "." + UUID.randomUUID() + ".tmp");
                    Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
                    moveAtomically(tmp, blob);
                }
                outputs.add(new CacheEntry.OutputFile(e.getKey(), hash, Files.size(source)));
            }
            CacheEntry entry = new CacheEntry(key, mojo, outputs, state);
            writeAtomically(entryPath(key), entry.encode());
            if (keyManifest != null) {
                writeAtomically(keyManifestPath(key), keyManifest);
            }
            return entry;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot store cache entry " + key, e);
        }
    }

    /** Copies every output file of {@code entry} into {@code baseDir}. */
    public void restoreFiles(CacheEntry entry, Path baseDir) {
        try {
            for (CacheEntry.OutputFile f : entry.files()) {
                Path target = baseDir.resolve(f.path()).normalize();
                if (!target.startsWith(baseDir)) {
                    throw new IllegalStateException("Cache entry escapes project directory: " + f.path());
                }
                Files.createDirectories(target.getParent());
                Files.copy(blobPath(f.hash()), target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot restore cache entry " + entry.key(), e);
        }
    }

    public Path blobPath(String hash) {
        return root.resolve("cas").resolve(hash.substring(0, 2)).resolve(hash);
    }

    public Path entryPath(String key) {
        return root.resolve("entries").resolve(key.substring(0, 2)).resolve(key);
    }

    public Path keyManifestPath(String key) {
        return root.resolve("keys").resolve(key.substring(0, 2)).resolve(key + ".txt");
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        moveAtomically(tmp, target);
    }

    private static void moveAtomically(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
