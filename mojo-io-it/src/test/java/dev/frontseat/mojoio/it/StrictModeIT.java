package dev.frontseat.mojoio.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Strict mode: writes outside declared outputs are reported and never cached. */
class StrictModeIT {

    private static final String PROJECT = "basic-multimodule";

    /** A declaration that forgets an output: the execution is reported and never stored. */
    @Test
    void undeclaredWritesAreReportedAndNotCached() throws Exception {
        Path cache = MavenBuild.cacheDir("strict-warn");
        MavenBuild build = MavenBuild.checkout(PROJECT, "strict-warn", "checkout", cache);
        declareResourcesWithoutOutputs(build.dir());

        MavenBuild.Result first = build.run("test-compile");
        assertTrue(first.log().contains("[mojo-io] UNDECLARED resources:resources (default-resources) @ lib-core "
                + "wrote 1 file(s) outside its declared outputs"), first.log());
        assertTrue(first.log().contains("target/classes/greeting.properties"), first.log());

        MavenBuild.Result second = build.run("test-compile");
        assertEquals("MISS", second.outcome("lib-core", "resources:resources"), "never stored\n" + second.log());
        assertEquals("HIT", second.outcome("lib-core", "compiler:compile"), second.log());
    }

    @Test
    void failModeFailsTheBuild() throws Exception {
        Path cache = MavenBuild.cacheDir("strict-fail");
        MavenBuild build = MavenBuild.checkout(PROJECT, "strict-fail", "checkout", cache);
        declareResourcesWithoutOutputs(build.dir());

        MavenBuild.Result result = build.runAllowingFailure("process-resources", "-Dmojoio.strict=fail");
        assertNotEquals(0, result.exitCode(), result.log());
        assertTrue(result.log().contains("UNDECLARED resources:resources"), result.log());
    }

    /** The built-in declarations themselves must be complete. */
    @Test
    void builtinDeclarationsHaveNoUndeclaredWrites() throws Exception {
        Path cache = MavenBuild.cacheDir("strict-builtin");
        MavenBuild.Result result = MavenBuild.checkout(PROJECT, "strict-builtin", "checkout", cache)
                .run("verify", "-Dmojoio.strict=fail");
        assertFalse(result.log().contains("UNDECLARED"), result.log());
    }

    /** cleanOutputs: classes of a deleted source cannot survive into the next entry. */
    @Test
    void cleanOutputsDropsLeftoversOfDeletedSources() throws Exception {
        Path cache = MavenBuild.cacheDir("strict-clean");
        MavenBuild build = MavenBuild.checkout(PROJECT, "strict-clean", "checkout", cache);
        Path extra = build.dir().resolve("lib-core/src/main/java/sample/core/Extra.java");
        Files.writeString(extra, "package sample.core;\npublic final class Extra {}\n");
        build.run("compile");
        Path extraClass = build.dir().resolve("lib-core/target/classes/sample/core/Extra.class");
        assertTrue(Files.exists(extraClass));

        Files.delete(extra);
        build.run("compile", "-Dmojoio.strict.cleanOutputs=true");
        assertFalse(Files.exists(extraClass), "leftover class of a deleted source");
        assertTrue(Files.exists(build.dir().resolve("lib-core/target/classes/sample/core/Greeter.class")));
    }

    private static void declareResourcesWithoutOutputs(Path root) throws Exception {
        Path dir = Files.createDirectories(root.resolve(".mvn/plugin-io"));
        Files.writeString(dir.resolve("resources.xml"), """
                <plugin-io xmlns="urn:mojo-io:plugin-io:1.0">
                  <plugin groupId="org.apache.maven.plugins" artifactId="maven-resources-plugin">
                    <mojo goal="resources" stamping="carry">
                      <inputs><resources parameter="resources"/></inputs>
                      <ignoredParameters>resources</ignoredParameters>
                    </mojo>
                  </plugin>
                </plugin-io>
                """);
    }
}
