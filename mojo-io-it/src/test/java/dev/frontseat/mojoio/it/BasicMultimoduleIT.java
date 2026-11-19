package dev.frontseat.mojoio.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** End-to-end behaviour against {@code src/it/projects/basic-multimodule} (lib-core &lt;- app). */
class BasicMultimoduleIT {

    private static final String PROJECT = "basic-multimodule";
    private static final List<String> MODULES = List.of("lib-core", "app");
    private static final List<String> COMPILE_GOALS = List.of(
            "jacoco:prepare-agent", "resources:resources", "compiler:compile",
            "resources:testResources", "compiler:testCompile");

    @Test
    void secondBuildRestoresEverythingFromTheCache() throws Exception {
        Path cache = MavenBuild.cacheDir("warm");
        MavenBuild build = MavenBuild.checkout(PROJECT, "warm", "checkout", cache);

        MavenBuild.Result cold = build.run("test-compile");
        for (String module : MODULES) {
            assertEquals("MISS", cold.outcome(module, "compiler:compile"), cold.log());
        }

        MavenBuild.deleteTree(build.dir().resolve("lib-core/target"));
        MavenBuild.deleteTree(build.dir().resolve("app/target"));

        MavenBuild.Result warm = build.run("test-compile");
        assertEquals(0, warm.count("MISS"), warm.log());
        for (String module : MODULES) {
            for (String goal : COMPILE_GOALS) {
                assertEquals("HIT", warm.outcome(module, goal), module + " " + goal + "\n" + warm.log());
            }
        }
        assertTrue(Files.isRegularFile(build.dir().resolve("lib-core/target/classes/sample/core/Greeter.class")));
        assertTrue(Files.isRegularFile(build.dir().resolve("app/target/test-classes/sample/app/AppTest.class")));
        assertEquals("greeting=Hello\nversion=1.0.0-SNAPSHOT\n",
                Files.readString(build.dir().resolve("lib-core/target/classes/greeting.properties")));
    }

    /**
     * The first-slice acceptance test: on a fresh checkout (at a different path than the one that
     * filled the cache), {@code mvn surefire:test} restores compile and JaCoCo from the cache and then
     * runs the tests with the agent attached.
     */
    @Test
    void surefireTestOnFreshCheckoutRestoresPrerequisitesAndRunsWithAgent() throws Exception {
        Path cache = MavenBuild.cacheDir("acceptance");
        MavenBuild.checkout(PROJECT, "acceptance", "first", cache).run("test-compile");

        MavenBuild fresh = MavenBuild.checkout(PROJECT, "acceptance", "second", cache);
        MavenBuild.Result result = fresh.run("surefire:test");

        for (String module : MODULES) {
            for (String goal : COMPILE_GOALS) {
                assertEquals("HIT", result.outcome(module, goal), module + " " + goal + "\n" + result.log());
            }
            assertEquals("MISS", result.outcome(module, "surefire:test"), result.log());
            // The agent path restored from the cache was relocated to this checkout and actually used.
            Path exec = fresh.dir().resolve(module + "/target/jacoco.exec");
            assertTrue(Files.size(exec) > 0, "jacoco.exec missing for " + module);
            assertTrue(Files.isRegularFile(fresh.dir().resolve(module + "/target/surefire-reports")
                    .resolve("TEST-sample." + (module.equals("app") ? "app.AppTest" : "core.GreeterTest") + ".xml")));
        }
        assertTrue(result.log().contains("Tests run: 2, Failures: 0"), result.log());

        // Unchanged inputs: the tests themselves now come from the cache.
        MavenBuild.Result again = fresh.run("surefire:test");
        assertEquals(0, again.count("MISS"), again.log());
        for (String module : MODULES) {
            assertEquals("HIT", again.outcome(module, "surefire:test"), again.log());
        }
        assertFalse(again.log().contains("T E S T S"), "tests should not have run again");
    }

    @Test
    void sourceChangeInvalidatesTheModuleAndItsDependents() throws Exception {
        Path cache = MavenBuild.cacheDir("invalidate");
        MavenBuild build = MavenBuild.checkout(PROJECT, "invalidate", "checkout", cache);
        build.run("test-compile");

        Path greeter = build.dir().resolve("lib-core/src/main/java/sample/core/Greeter.java");
        Files.writeString(greeter, Files.readString(greeter).replace("\", \" + name", "\", dear \" + name"));

        MavenBuild.Result result = build.run("test-compile");
        assertEquals("HIT", result.outcome("lib-core", "resources:resources"), result.log());
        assertEquals("MISS", result.outcome("lib-core", "compiler:compile"), result.log());
        // app's compile classpath contains lib-core's classes, so its key changes too.
        assertEquals("MISS", result.outcome("app", "compiler:compile"), result.log());
        assertEquals("HIT", result.outcome("app", "resources:testResources"), result.log());
    }

    @Test
    void projectDeclarationsOverrideBuiltins() throws Exception {
        Path cache = MavenBuild.cacheDir("override");
        MavenBuild build = MavenBuild.checkout(PROJECT, "override", "checkout", cache);
        Path dir = Files.createDirectories(build.dir().resolve(".mvn/plugin-io"));
        Files.writeString(dir.resolve("surefire.xml"), """
                <plugin-io xmlns="urn:mojo-io:plugin-io:1.0">
                  <plugin groupId="org.apache.maven.plugins" artifactId="maven-surefire-plugin">
                    <mojo goal="test" cacheable="false" reason="integration tests hit a live service"/>
                  </plugin>
                </plugin-io>
                """);

        build.run("test");
        MavenBuild.Result again = build.run("test");
        for (String module : MODULES) {
            assertEquals("HIT", again.outcome(module, "compiler:compile"), again.log());
            assertEquals("SKIP", again.outcome(module, "surefire:test"), again.log());
        }
        assertTrue(again.log().contains("integration tests hit a live service"), again.log());
        assertTrue(again.log().contains("T E S T S"), "tests must run every time");
    }

    /**
     * Regression: after package, downstream modules compile against the upstream jar. The jar is
     * re-packed on every build (new entry timestamps), which must not change downstream keys.
     */
    @Test
    void repeatedVerifyIsAllHitsDespiteRepackedJars() throws Exception {
        Path cache = MavenBuild.cacheDir("verify-twice");
        MavenBuild build = MavenBuild.checkout(PROJECT, "verify-twice", "checkout", cache);
        build.run("verify");
        MavenBuild.Result again = build.run("verify");
        assertEquals(0, again.count("MISS"), again.log());
        for (String module : MODULES) {
            assertEquals("HIT", again.outcome(module, "compiler:compile"), again.log());
            assertEquals("HIT", again.outcome(module, "surefire:test"), again.log());
        }
    }
}
