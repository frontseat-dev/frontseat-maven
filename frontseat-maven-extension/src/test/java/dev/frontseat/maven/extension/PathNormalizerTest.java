package dev.frontseat.maven.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathNormalizerTest {

    private final PathNormalizer n = new PathNormalizer(
            Path.of("/work/repo/lib"), Path.of("/work/repo"), Path.of("/home/me/.m2/repository"));

    @Test
    void longestRootWins() {
        assertEquals("@@BASEDIR@@/target/classes", n.normalize("/work/repo/lib/target/classes"));
        assertEquals("@@ROOT@@/app/target/classes", n.normalize("/work/repo/app/target/classes"));
    }

    @Test
    void onlyMatchesOnPathBoundaries() {
        assertEquals("/work/repo2/x", n.normalize("/work/repo2/x"));
        assertEquals("/work/repo/library/x".replace("/work/repo", "@@ROOT@@"), n.normalize("/work/repo/library/x"));
    }

    @Test
    void normalizesPathsEmbeddedInArguments() {
        String argLine = "-javaagent:/home/me/.m2/repository/org/jacoco/agent.jar=destfile=/work/repo/lib/target/jacoco.exec";
        String normalized = n.normalize(argLine);
        assertEquals("-javaagent:@@M2REPO@@/org/jacoco/agent.jar=destfile=@@BASEDIR@@/target/jacoco.exec", normalized);
        assertEquals(argLine, n.denormalize(normalized));
    }

    @Test
    void relocatesToAnotherCheckout() {
        PathNormalizer other = new PathNormalizer(
                Path.of("/ci/build/lib"), Path.of("/ci/build"), Path.of("/cache/m2"));
        assertEquals("/ci/build/lib/target/jacoco.exec",
                other.denormalize(n.normalize("/work/repo/lib/target/jacoco.exec")));
    }

    @Test
    void reportsMissingRepositoryFiles(@TempDir Path tmp) throws Exception {
        Path repo = tmp.resolve("m2");
        Files.createDirectories(repo.resolve("org"));
        Files.writeString(repo.resolve("org/present.jar"), "");
        PathNormalizer local = new PathNormalizer(tmp.resolve("p"), tmp, repo);
        assertTrue(local.missingRepositoryFiles("-javaagent:@@M2REPO@@/org/present.jar=x").isEmpty());
        assertEquals(1, local.missingRepositoryFiles("-javaagent:@@M2REPO@@/org/absent.jar=x").size());
    }
}
