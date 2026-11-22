package dev.frontseat.mojoio.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frontseat.mojoio.cache.CacheEntry;
import dev.frontseat.mojoio.spec.StateFile;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StateFilesTest {

    @AfterEach
    void reset() {
        StateFiles.reset();
    }

    private static MavenProject project(Path dir) {
        Model model = new Model();
        Build build = new Build();
        build.setDirectory(dir.resolve("target").toString());
        model.setBuild(build);
        MavenProject p = new MavenProject(model);
        p.setFile(new File(dir.toFile(), "pom.xml"));
        return p;
    }

    private static MojoExecution execution(String artifactId, String goal, String id) {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.example");
        plugin.setArtifactId(artifactId);
        plugin.setVersion("1");
        return new MojoExecution(plugin, goal, id);
    }

    /**
     * A clean bound to an early phase deletes the state an earlier mojo of the same build recorded,
     * while its effect still holds in memory (Tycho's demo cleans after computing the qualifier): the
     * file is written again, so a later build that resumes from target/ replays it.
     */
    @Test
    void stateDeletedByALaterMojoIsWrittenAgain(@TempDir Path dir) throws Exception {
        MavenProject project = project(dir);
        MojoExecution qualifier = execution("packaging-plugin", "build-qualifier", "default");
        StateFiles.record(project, qualifier, List.of(new CacheEntry.StateRecord("property", "buildQualifier", "v1")),
                null, "validate");
        Path file = StateFiles.file(project, qualifier);
        assertTrue(Files.exists(file));

        deleteTree(dir.resolve("target")); // the clean
        assertFalse(Files.exists(file));
        StateFiles.record(project, execution("other-plugin", "check", "default"), List.of(), null, "validate");

        assertTrue(Files.exists(file));
        assertEquals("v1", StateFile.read(file).changes().get(0).value());
    }

    @Test
    void anotherBuildDoesNotRewriteTheLastOnesFiles(@TempDir Path dir) throws Exception {
        MavenProject project = project(dir);
        MojoExecution qualifier = execution("packaging-plugin", "build-qualifier", "default");
        StateFiles.record(project, qualifier, List.of(), null, "validate");
        StateFiles.reset(); // the build ended (mvnd keeps the JVM)
        deleteTree(dir.resolve("target"));
        StateFiles.record(project, execution("other-plugin", "check", "default"), List.of(), null, "validate");
        assertFalse(Files.exists(StateFiles.file(project, qualifier)));
    }

    private static void deleteTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
