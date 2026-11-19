package dev.frontseat.mojoio.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * Version bumps (see docs/versioning.md): outputs that merely carry the version are reused and
 * restamped; executions that act on it, or whose outputs embed it unrewritably, run again.
 */
class VersioningIT {

    @Test
    void versionBumpReusesCarriedOutputsAndRestampsTheirMetadata() throws Exception {
        Path cache = MavenBuild.cacheDir("version-bump");
        MavenBuild.checkout("basic-multimodule", "version-bump", "v1", cache).run("verify");

        MavenBuild v2 = MavenBuild.checkout("basic-multimodule", "version-bump", "v2", cache);
        setVersion(v2.dir(), "1.0.0-SNAPSHOT", "1.1.0-SNAPSHOT");
        MavenBuild.Result result = v2.run("verify");

        for (String module : List.of("lib-core", "app")) {
            for (String goal : List.of("resources:resources", "compiler:compile", "compiler:testCompile", "jar:jar",
                    "source:jar-no-fork")) {
                assertEquals("HIT", result.outcome(module, goal), module + " " + goal + "\n" + result.log());
            }
            // Tests may act on the version (GreeterTest compares it): they run again, and pass,
            // which also proves the restamped filtered resource carries the new version.
            assertEquals("MISS", result.outcome(module, "surefire:test"), result.log());
        }
        assertEquals("HIT", result.outcome("lib-core", "jar:test-jar"), result.log());
        assertTrue(Files.isRegularFile(v2.dir().resolve("lib-core/target/lib-core-1.1.0-SNAPSHOT-tests.jar")));
        Path sources = v2.dir().resolve("lib-core/target/lib-core-1.1.0-SNAPSHOT-sources.jar");
        try (ZipFile zip = new ZipFile(sources.toFile())) {
            assertTrue(entry(zip, "greeting.properties").contains("version=${project.version}"),
                    "sources carry the unfiltered template, which must stay as it is");
        }
        Path jar = v2.dir().resolve("lib-core/target/lib-core-1.1.0-SNAPSHOT.jar");
        assertTrue(Files.isRegularFile(jar), "restored jar must carry the new version in its name");
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            assertTrue(entry(zip, "META-INF/maven/com.example.samples/lib-core/pom.properties")
                    .contains("version=1.1.0-SNAPSHOT"));
            assertTrue(entry(zip, "META-INF/maven/com.example.samples/lib-core/pom.xml").contains("1.1.0-SNAPSHOT"));
            assertTrue(entry(zip, "greeting.properties").contains("version=1.1.0-SNAPSHOT"));
        }
        assertEquals("greeting=Hello\nversion=1.1.0-SNAPSHOT\n",
                Files.readString(v2.dir().resolve("lib-core/target/classes/greeting.properties")));
        String state = Files.readString(v2.dir().resolve("lib-core/target/mojo-io/state/maven-jar-plugin-jar-default-jar.state"));
        assertTrue(state.contains("STAMP%3Aproject.version"), "state records the artifact file with a stamp token");
    }

    @Test
    void stampsCompiledIntoClassesPinTheEntryToTheVersion() throws Exception {
        Path cache = MavenBuild.cacheDir("version-pinned");
        MavenBuild.Result first = MavenBuild.checkout("version-codegen", "version-pinned", "v1", cache).run("verify");
        assertTrue(first.log().contains("pinned to stamp values"), first.log());

        MavenBuild v2 = MavenBuild.checkout("version-codegen", "version-pinned", "v2", cache);
        setVersion(v2.dir(), "1.0.0-SNAPSHOT", "2.0.0-SNAPSHOT");
        MavenBuild.Result bumped = v2.run("verify");
        // A stale Version.class from the cache would fail VersionTest; it must be recompiled instead.
        assertEquals("MISS", bumped.outcome("version-codegen", "compiler:compile"), bumped.log());
        assertEquals("MISS", bumped.outcome("version-codegen", "jar:jar"), bumped.log());
        assertTrue(bumped.log().contains("Tests run: 1, Failures: 0"), bumped.log());

        MavenBuild.Result again = v2.run("verify");
        assertEquals("HIT", again.outcome("version-codegen", "compiler:compile"), again.log());
        assertEquals("HIT", again.outcome("version-codegen", "jar:jar"), again.log());
    }

    private static void setVersion(Path dir, String from, String to) throws IOException {
        try (Stream<Path> poms = Files.walk(dir)) {
            for (Path pom : poms.filter(p -> p.getFileName().toString().equals("pom.xml")).toList()) {
                Files.writeString(pom, Files.readString(pom).replace(from, to));
            }
        }
    }

    private static String entry(ZipFile zip, String name) {
        try (InputStream in = zip.getInputStream(zip.getEntry(name))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
