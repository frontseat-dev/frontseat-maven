package dev.frontseat.mojoio.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The compiler with annotationProcessorPaths: cacheable, and a different processor version is a
 * different fingerprint. A SNAPSHOT processor can change under the same version: not cacheable.
 */
class AnnotationProcessorsIT {

    private static final String PROJECT = "annotation-processors";

    private static JsonNode compile(MavenBuild build, String... flags) {
        String[] goals = new String[flags.length + 1];
        goals[0] = "compile";
        System.arraycopy(flags, 0, goals, 1, flags.length);
        return TaskGraph.execution(TaskGraph.project(TaskGraph.export(build, goals), PROJECT), "compile");
    }

    @Test
    void processorPathsCountByVersion() {
        MavenBuild build = MavenBuild.checkout(PROJECT, "processors", "checkout");
        JsonNode compile = compile(build);
        assertTrue(compile.get("cacheable").asBoolean(), compile.toString());
        assertEquals(compile.get("fingerprint"), compile(build).get("fingerprint"), "stable across exports");
        assertNotEquals(compile.get("fingerprint"), compile(build, "-Dlombok.version=1.18.34").get("fingerprint"),
                "another processor version");

        JsonNode snapshot = compile(build, "-Dlombok.version=1.18.99-SNAPSHOT");
        assertFalse(snapshot.get("cacheable").asBoolean());
        assertTrue(snapshot.get("reason").asText().contains("can change under the same version"), snapshot.toString());
    }

    /** No version in the path: the compiler takes it from dependency management, and so does the fingerprint. */
    @Test
    void managedProcessorVersionsAreInTheFingerprint() throws Exception {
        MavenBuild build = MavenBuild.checkout(PROJECT, "processors-managed", "checkout");
        Path pom = build.dir().resolve("pom.xml");
        Files.writeString(pom, Files.readString(pom)
                .replace("<version>${lombok.version}</version>\n            </path>", "</path>")
                .replace("<annotationProcessorPaths>", "<annotationProcessorPathsUseDepMgmt>true</annotationProcessorPathsUseDepMgmt>\n          <annotationProcessorPaths>")
                .replace("  <dependencies>", """
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                        <version>${lombok.version}</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>

                  <dependencies>"""));
        JsonNode compile = compile(build);
        assertTrue(compile.get("cacheable").asBoolean(), compile.toString());
        assertNotEquals(compile.get("fingerprint"), compile(build, "-Dlombok.version=1.18.34").get("fingerprint"),
                "another managed processor version");
    }
}
