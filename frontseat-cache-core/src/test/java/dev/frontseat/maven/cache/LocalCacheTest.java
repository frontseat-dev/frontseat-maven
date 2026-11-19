package dev.frontseat.maven.cache;

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
}
