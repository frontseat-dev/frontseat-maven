package dev.frontseat.mojoio.it;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * A mojo that forks a goal ({@code pmd:check} forks {@code pmd:pmd}) is one unit with it: the forked
 * goal's inputs and outputs are the check's, so a build system caches and invalidates them together.
 */
class ForkedGoalsIT {

    private static final String PROJECT = "forked-check";

    @Test
    void graphDescribesTheUnit() {
        MavenBuild build = MavenBuild.checkout(PROJECT, "forked-graph", "checkout");
        JsonNode check = TaskGraph.execution(TaskGraph.project(TaskGraph.export(build, "verify"), PROJECT), "check");
        assertTrue(check.get("cacheable").asBoolean(), check.toString());
        assertTrue(check.get("inputs").toString().contains(
                "fork(org.apache.maven.plugins:maven-pmd-plugin:3.28.0:pmd@pmd).compileSourceRoots"), check.toString());
        assertTrue(check.get("outputs").toString().contains("\"observed\":true"), check.toString());
    }

    @Test
    void checkAndItsForkedGoalAreOneCacheUnit() throws Exception {
        Path cache = MavenBuild.emptyDir("forked", "bs");
        new ReferenceBuildSystem(MavenBuild.checkout(PROJECT, "forked", "a"), cache).build("verify");

        MavenBuild b = MavenBuild.checkout(PROJECT, "forked", "b");
        ReferenceBuildSystem bs = new ReferenceBuildSystem(b, cache);
        assertTrue(bs.build("verify").stream().allMatch(ReferenceBuildSystem.Outcome::hit));
        assertTrue(Files.isRegularFile(b.dir().resolve("target/pmd.xml")), "the forked goal's report is restored");

        // What only the forked goal reads (the sources) is the check's input: a violation is found, not
        // hidden by a stale hit.
        Path source = b.dir().resolve("src/main/java/sample/forked/Counter.java");
        Files.writeString(source, Files.readString(source).replace("private int count;",
                "private int count;\n\n    private int unused;"));
        ReferenceBuildSystem.TaskFailed failed = assertThrows(ReferenceBuildSystem.TaskFailed.class, () -> bs.build("verify"));
        assertTrue(failed.getMessage().contains("has found 1 violation"), failed.getMessage());
    }
}
