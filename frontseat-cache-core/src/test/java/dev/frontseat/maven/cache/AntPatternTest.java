package dev.frontseat.maven.cache;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AntPatternTest {

    @Test
    void doubleStarMatchesZeroOrMoreDirectories() {
        AntPattern p = AntPattern.compile("**/*.properties");
        assertTrue(p.matches("a.properties"));
        assertTrue(p.matches("x/y/a.properties"));
        assertFalse(p.matches("x/a.xml"));
    }

    @Test
    void singleStarStaysWithinASegment() {
        AntPattern p = AntPattern.compile("*.txt");
        assertTrue(p.matches("a.txt"));
        assertFalse(p.matches("dir/a.txt"));
    }

    @Test
    void trailingSlashMeansEverythingBelow() {
        assertTrue(AntPattern.compile("gen/").matches("gen/a/b.java"));
    }

    @Test
    void includesAndExcludes() {
        assertTrue(AntPattern.selected("a/b.txt", List.of(), List.of()));
        assertFalse(AntPattern.selected("a/b.txt", List.of("**/*.xml"), List.of()));
        assertFalse(AntPattern.selected("a/.git/config", List.of(), AntPattern.DEFAULT_EXCLUDES));
    }
}
