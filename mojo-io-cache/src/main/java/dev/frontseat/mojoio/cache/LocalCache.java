package dev.frontseat.mojoio.cache;

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
                Path blob = blobPath(f.hash());
                if (!Files.isRegularFile(blob) || Files.size(blob) != f.size()) {
                    return Optional.empty(); // incomplete or damaged entry: a miss
                }
            }
            return Optional.of(entry);
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** A file to store: its (possibly stamp-tokenized) relative path, where it is now, and its restamp mode. */
    public record StoredFile(String path, Path source, Restamping.Mode mode) {}

    /**
     * Stores an entry. {@code files} maps each output path (relative to the project base directory)
     * to the file on disk to copy into the store.
     */
    public CacheEntry store(String key, String mojo, Map<String, Path> files, List<CacheEntry.StateRecord> state,
                            String keyManifest) {
        List<StoredFile> stored = new ArrayList<>();
        files.forEach((path, source) -> stored.add(new StoredFile(path, source, Restamping.Mode.RAW)));
        return store(key, mojo, stored, state, Map.of(), keyManifest);
    }

    /** Stores a redirect: outputs for {@code key} are pinned to exact stamp values (see {@link CacheEntry}). */
    public void storeRedirect(String key, String mojo, String reason) {
        try {
            writeAtomically(entryPath(key), CacheEntry.redirect(key, mojo, reason).encode());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot store cache entry " + key, e);
        }
    }

    public CacheEntry store(String key, String mojo, List<StoredFile> files, List<CacheEntry.StateRecord> state,
                            Map<String, String> stamps, String keyManifest) {
        List<CacheEntry.OutputFile> outputs = new ArrayList<>();
        try {
            for (StoredFile e : files) {
                // Copy first, then hash the copy: a blob is named after exactly the bytes it holds, even if
                // the source is still being written to.
                Path staging = root.resolve("cas").resolve("staging");
                Files.createDirectories(staging);
                Path tmp = staging.resolve(UUID.randomUUID() + ".tmp");
                Files.copy(e.source(), tmp, StandardCopyOption.REPLACE_EXISTING);
                String hash = Hashing.sha256(tmp);
                long size = Files.size(tmp);
                Path blob = blobPath(hash);
                if (Files.isRegularFile(blob) && Files.size(blob) == size) {
                    Files.delete(tmp);
                } else {
                    Files.createDirectories(blob.getParent());
                    moveAtomically(tmp, blob);
                }
                outputs.add(new CacheEntry.OutputFile(e.path(), hash, size, e.mode()));
            }
            CacheEntry entry = new CacheEntry(key, mojo, outputs, state, stamps, null);
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
        restoreFiles(entry, baseDir, Stamps.NONE);
    }

    /**
     * Copies every output file of {@code entry} into {@code baseDir}, rewriting stamp tokens in paths and
     * stamp values in restampable content for the {@code current} stamps.
     */
    public void restoreFiles(CacheEntry entry, Path baseDir, Stamps current) {
        try {
            for (CacheEntry.OutputFile f : entry.files()) {
                Path target = baseDir.resolve(current.detokenize(f.path())).normalize();
                if (!target.startsWith(baseDir)) {
                    throw new IllegalStateException("Cache entry escapes project directory: " + f.path());
                }
                Files.createDirectories(target.getParent());
                Path blob = blobPath(f.hash());
                if (!intact(blob, f.hash())) {
                    // Never restore bytes that are not what the entry names. Drop the entry so the next
                    // build misses instead of failing again.
                    Files.deleteIfExists(entryPath(entry.key()));
                    Files.deleteIfExists(blob);
                    throw new IllegalStateException("Cache blob " + f.hash() + " is damaged (entry " + entry.key() + " removed)");
                }
                Restamping.restamp(blob, target, f.mode(), entry.stamps(), current.values());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot restore cache entry " + entry.key(), e);
        }
    }

    /** Blobs already checked in this JVM, by path, size and modification time. */
    private static final Map<String, Boolean> VERIFIED = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean intact(Path blob, String hash) throws IOException {
        if (!Files.isRegularFile(blob)) {
            return false;
        }
        String id = blob + "|" + Files.size(blob) + "|" + Files.getLastModifiedTime(blob).toMillis();
        if (VERIFIED.containsKey(id)) {
            return true;
        }
        if (!Hashing.sha256(blob).equals(hash)) {
            return false;
        }
        VERIFIED.put(id, Boolean.TRUE);
        return true;
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
