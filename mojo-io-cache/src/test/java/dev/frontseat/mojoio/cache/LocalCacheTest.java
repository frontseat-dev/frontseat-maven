package dev.frontseat.mojoio.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalCacheTest {

    @Test
    void storeLookupAndRestoreRoundTrip(@TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("project");
        Path classFile = project.resolve("target/classes/a/B.class");
        Files.createDirectories(classFile.getParent());
        Files.writeString(classFile, "bytecode");

        LocalCache cache = new LocalCache(tmp.resolve("cache"));
        String key = new CacheKey().add("x", "y").hash();
        Map<String, Path> files = new LinkedHashMap<>();
        files.put("target/classes/a/B.class", classFile);
        List<CacheEntry.StateRecord> state = List.of(
                new CacheEntry.StateRecord("property", "argLine", "-javaagent:@@M2REPO@@/a b.jar=destfile=x"));
        cache.store(key, "g:a:1:goal", files, state, "x = y\n");

        CacheEntry entry = cache.lookup(key).orElseThrow();
        assertEquals(state, entry.state());
        assertEquals("target/classes/a/B.class", entry.files().get(0).path());

        Path fresh = tmp.resolve("fresh");
        cache.restoreFiles(entry, fresh);
        assertEquals("bytecode", Files.readString(fresh.resolve("target/classes/a/B.class")));
        assertTrue(Files.exists(cache.keyManifestPath(key)));
    }

    @Test
    void missingKeyIsAMiss(@TempDir Path tmp) {
        assertTrue(new LocalCache(tmp).lookup(new CacheKey().hash()).isEmpty());
    }

    @Test
    void entryEncodingHandlesEmptyAndNullValues() {
        CacheEntry e = new CacheEntry("k", "m", List.of(),
                List.of(new CacheEntry.StateRecord("property", "p", ""),
                        new CacheEntry.StateRecord("property", "q", null)));
        assertEquals(e, CacheEntry.decode(e.encode()));
    }

    /** A blob that no longer holds the bytes it is named after is never restored. */
    @Test
    void damagedBlobsAreNotRestored(@TempDir Path tmp) throws Exception {
        LocalCache cache = new LocalCache(tmp.resolve("cache"));
        Path output = tmp.resolve("project/target/out.txt");
        Files.createDirectories(output.getParent());
        Files.writeString(output, "content");
        CacheEntry entry = cache.store("k".repeat(64), "g:a:1:goal", Map.of("target/out.txt", output), List.of(), null);
        Path blob = cache.blobPath(entry.files().get(0).hash());

        // Same size, other bytes: only reading it shows the damage.
        Files.writeString(blob, "CONTENT");
        Path restoreTo = tmp.resolve("elsewhere");
        Files.createDirectories(restoreTo);
        CacheEntry found = cache.lookup("k".repeat(64)).orElseThrow();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> cache.restoreFiles(found, restoreTo));
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(restoreTo.resolve("target/out.txt")));
        org.junit.jupiter.api.Assertions.assertTrue(cache.lookup("k".repeat(64)).isEmpty(), "the entry is gone: next time a miss");

        // Truncated: already the lookup says no.
        CacheEntry again = cache.store("m".repeat(64), "g:a:1:goal", Map.of("target/out.txt", output), List.of(), null);
        Files.writeString(cache.blobPath(again.files().get(0).hash()), "con");
        org.junit.jupiter.api.Assertions.assertTrue(cache.lookup("m".repeat(64)).isEmpty());
    }
}
