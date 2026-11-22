package dev.frontseat.mojoio.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The compiler with annotationProcessorPaths: cached, and a different processor version is a
 * different key. A SNAPSHOT processor can change under the same version: never cached.
 */
class AnnotationProcessorsIT {

    private static final String PROJECT = "annotation-processors";
    private static final String COMPILE = "compiler:compile";

    @Test
    void processorPathsAreHashedByVersion() throws Exception {
        Path cache = MavenBuild.cacheDir("processors");
        MavenBuild build = MavenBuild.checkout(PROJECT, "processors", "checkout", cache);
        build.run("compile");
        assertTrue(Files.isRegularFile(build.dir().resolve("target/classes/sample/ap/Point.class")));

        MavenBuild.Result warm = build.run("clean", "compile");
        assertEquals("HIT", warm.outcome(PROJECT, COMPILE), warm.log());

        MavenBuild.Result other = build.run("clean", "compile", "-Dlombok.version=1.18.34");
        assertEquals("MISS", other.outcome(PROJECT, COMPILE), "another processor version\n" + other.log());

        MavenBuild.Result snapshot = build.runAllowingFailure("clean", "compile", "-Dlombok.version=1.18.99-SNAPSHOT");
        assertTrue(snapshot.log().contains("can change under the same version"), snapshot.log());
    }

    /** No version in the path: the compiler takes it from dependency management, and so does the key. */
    @Test
    void managedProcessorVersionsAreInTheKey() throws Exception {
        Path cache = MavenBuild.cacheDir("processors-managed");
        MavenBuild build = MavenBuild.checkout(PROJECT, "processors-managed", "checkout", cache);
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
        build.run("compile");
        assertEquals("HIT", build.run("clean", "compile").outcome(PROJECT, COMPILE));
        MavenBuild.Result other = build.run("clean", "compile", "-Dlombok.version=1.18.34");
        assertEquals("MISS", other.outcome(PROJECT, COMPILE), "another managed processor version\n" + other.log());
    }
}
