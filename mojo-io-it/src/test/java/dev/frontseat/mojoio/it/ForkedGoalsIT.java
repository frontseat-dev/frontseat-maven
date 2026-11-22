package dev.frontseat.mojoio.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * A mojo that forks a goal ({@code pmd:check} forks {@code pmd:pmd}) is cached as one unit with it: the
 * forked goal's inputs are in the check's key, and a hit runs neither.
 */
class ForkedGoalsIT {

    private static final String PROJECT = "forked-check";
    private static final String MODULE = "forked-check";

    @Test
    void checkAndItsForkedGoalAreOneCacheUnit() throws Exception {
        Path cache = MavenBuild.cacheDir("forked");
        MavenBuild build = MavenBuild.checkout(PROJECT, "forked", "checkout", cache);

        MavenBuild.Result first = build.run("verify");
        assertEquals("MISS", first.outcome(MODULE, "pmd:check"), first.log());
        assertTrue(first.log().contains(":pmd (pmd) @ forked-check"), "the forked goal ran\n" + first.log());

        MavenBuild.Result second = build.run("clean", "verify");
        assertEquals("HIT", second.outcome(MODULE, "pmd:check"), second.log());
        assertFalse(second.log().contains(":pmd (pmd) @ forked-check"), "a hit runs neither goal\n" + second.log());
        assertTrue(Files.isRegularFile(build.dir().resolve("target/pmd.xml")), "the forked goal's report is restored");

        // What only the forked goal reads (the sources) is in the check's key: a violation is found,
        // not hidden by a stale hit.
        Path source = build.dir().resolve("src/main/java/sample/forked/Counter.java");
        Files.writeString(source, Files.readString(source).replace("private int count;",
                "private int count;\n\n    private int unused;"));
        MavenBuild.Result violation = build.runAllowingFailure("verify");
        assertNotEquals(0, violation.exitCode(), violation.log());
        assertTrue(violation.log().contains("has found 1 violation"), violation.log());
    }

    @Test
    void graphDescribesTheUnit() throws Exception {
        Path cache = MavenBuild.cacheDir("forked-graph");
        MavenBuild build = MavenBuild.checkout(PROJECT, "forked-graph", "checkout", cache);
        build.run("verify", "-Dmojoio.graph.output=graph.json");
        JsonNode graph = new ObjectMapper().readTree(build.dir().resolve("graph.json").toFile());
        for (JsonNode e : graph.get("projects").get(0).get("executions")) {
            if (e.get("goal").asText().equals("check")) {
                assertTrue(e.get("cacheable").asBoolean(), e.toString());
                assertTrue(e.get("inputs").toString().contains("fork(org.apache.maven.plugins:maven-pmd-plugin:3.28.0:pmd@pmd).compileSourceRoots"),
                        e.toString());
                return;
            }
        }
        throw new AssertionError("no check execution in the graph");
    }
}
