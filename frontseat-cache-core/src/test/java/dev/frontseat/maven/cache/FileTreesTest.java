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
}
