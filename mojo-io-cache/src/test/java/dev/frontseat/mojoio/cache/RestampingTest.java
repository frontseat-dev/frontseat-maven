package dev.frontseat.mojoio.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestampingTest {

    private static final Stamps OLD = new Stamps(Map.of("project.version", "1.0.0-SNAPSHOT"));
    private static final Map<String, String> NEW = Map.of("project.version", "1.1.0");

    @Test
    void shortValuesAreNotStamps() {
        assertTrue(new Stamps(Map.of("project.version", "1.0")).isEmpty());
    }

    @Test
    void tokenizesTextButNotBinaryContent() {
        byte[] text = "version=1.0.0-SNAPSHOT\n".getBytes(StandardCharsets.UTF_8);
        assertEquals("version=@@STAMP:project.version@@\n", new String(OLD.tokenizeContent(text), StandardCharsets.UTF_8));
        byte[] binary = ("\0" + "1.0.0-SNAPSHOT").getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(binary, OLD.tokenizeContent(binary));
    }

    @Test
    void classifiesOutputs(@TempDir Path tmp) throws Exception {
        assertEquals(Restamping.Mode.RAW, Restamping.classify(write(tmp, "a.txt", "nothing here"), OLD));
        assertEquals(Restamping.Mode.TEXT, Restamping.classify(write(tmp, "b.properties", "v=1.0.0-SNAPSHOT"), OLD));
        assertEquals(Restamping.Mode.UNSTAMPABLE, Restamping.classify(write(tmp, "C.class", "\0\0v1.0.0-SNAPSHOT"), OLD));
        assertEquals(Restamping.Mode.ARCHIVE, Restamping.classify(jar(tmp.resolve("ok.jar"),
                "META-INF/maven/g/a/pom.properties", "version=1.0.0-SNAPSHOT\n", "a/B.class", "\0bytes"), OLD));
        assertEquals(Restamping.Mode.UNSTAMPABLE, Restamping.classify(jar(tmp.resolve("bad.jar"),
                "a/Version.class", "\0const 1.0.0-SNAPSHOT"), OLD));
    }

    @Test
    void rewritesTextEntriesOfArchivesAndKeepsTheRest(@TempDir Path tmp) throws Exception {
        Path source = jar(tmp.resolve("lib.jar"),
                "META-INF/MANIFEST.MF", "Implementation-Version: 1.0.0-SNAPSHOT\n",
                "META-INF/maven/g/a/pom.properties", "version=1.0.0-SNAPSHOT\n",
                "a/B.class", "\0bytes 1.0.0-SNAPSHOT-not-touched-in-binary");
        Path target = tmp.resolve("out/lib.jar");
        Restamping.restamp(source, target, Restamping.Mode.ARCHIVE, OLD.values(), NEW);
        try (ZipFile zip = new ZipFile(target.toFile())) {
            assertEquals(List.of("META-INF/MANIFEST.MF", "META-INF/maven/g/a/pom.properties", "a/B.class"),
                    java.util.Collections.list(zip.entries()).stream().map(ZipEntry::getName).toList());
            assertEquals("version=1.1.0\n", read(zip, "META-INF/maven/g/a/pom.properties"));
            assertEquals("Implementation-Version: 1.1.0\n", read(zip, "META-INF/MANIFEST.MF"));
            assertEquals("\0bytes 1.0.0-SNAPSHOT-not-touched-in-binary", read(zip, "a/B.class"));
            assertEquals(1_000_000_000L, zip.getEntry("a/B.class").getTime() / 1000 * 1000);
        }
    }

    @Test
    void entryRoundTripsStampsModesAndRedirects() {
        CacheEntry e = new CacheEntry("k", "m",
                List.of(new CacheEntry.OutputFile("target/lib-@@STAMP:project.version@@.jar", "h", 3, Restamping.Mode.ARCHIVE)),
                List.of(), Map.of("project.version", "1.0.0-SNAPSHOT"), null);
        assertEquals(e, CacheEntry.decode(e.encode()));
        CacheEntry r = CacheEntry.redirect("k", "m", "a class embeds project.version");
        assertTrue(CacheEntry.decode(r.encode()).isRedirect());
    }

    private static Path write(Path dir, String name, String content) throws Exception {
        Path f = dir.resolve(name);
        Files.writeString(f, content);
        return f;
    }

    private static Path jar(Path file, String... nameAndContent) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (int i = 0; i < nameAndContent.length; i += 2) {
                ZipEntry e = new ZipEntry(nameAndContent[i]);
                e.setTime(1_000_000_000L);
                out.putNextEntry(e);
                out.write(nameAndContent[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }

    private static String read(ZipFile zip, String name) throws Exception {
        try (InputStream in = zip.getInputStream(zip.getEntry(name))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
