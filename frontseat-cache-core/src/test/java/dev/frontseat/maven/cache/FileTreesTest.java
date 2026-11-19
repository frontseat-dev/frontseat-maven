package dev.frontseat.maven.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileTreesTest {

    @Test
    void treeHashIsLocationIndependentAndContentSensitive(@TempDir Path tmp) throws Exception {
        Path a = write(tmp.resolve("a"), "x/One.java", "class One {}");
        Path b = write(tmp.resolve("b"), "x/One.java", "class One {}");
        assertEquals(FileTrees.hash(a), FileTrees.hash(b));

        Files.writeString(b.resolve("x/One.java"), "class One { }");
        assertNotEquals(FileTrees.hash(a), FileTrees.hash(b));
    }

    @Test
    void renamesChangeTheHash(@TempDir Path tmp) throws Exception {
        Path a = write(tmp.resolve("a"), "One.java", "same");
        Path b = write(tmp.resolve("b"), "Two.java", "same");
        assertNotEquals(FileTrees.hash(a), FileTrees.hash(b));
    }

    @Test
    void missingPathIsAbsent(@TempDir Path tmp) {
        assertEquals(FileTrees.ABSENT, FileTrees.hash(tmp.resolve("nope")));
    }

    private static Path write(Path root, String rel, String content) throws Exception {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
        return root;
    }

    @Test
    void archivesHashByEntriesNotTimestamps(@TempDir Path tmp) throws Exception {
        Path a = jar(tmp.resolve("a.jar"), 1_000_000L, "x/A.class", "B.class");
        Path b = jar(tmp.resolve("b.jar"), 2_000_000_000L, "B.class", "x/A.class"); // other time and order
        assertNotEquals(Hashing.sha256(a), Hashing.sha256(b));
        assertEquals(FileTrees.hashClasspathEntry(a), FileTrees.hashClasspathEntry(b));

        Path c = jar(tmp.resolve("c.jar"), 1_000_000L, "x/A.class", "C.class");
        assertNotEquals(FileTrees.hashClasspathEntry(a), FileTrees.hashClasspathEntry(c));
    }

    private static Path jar(Path file, long time, String... names) throws Exception {
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
            for (String name : names) {
                java.util.zip.ZipEntry e = new java.util.zip.ZipEntry(name);
                e.setTime(time);
                out.putNextEntry(e);
                out.write(("content of " + name.replace("C", "B")).getBytes());
                out.closeEntry();
            }
        }
        return file;
    }
}
