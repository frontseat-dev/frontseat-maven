package dev.frontseat.mojoio.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a build system gets from mojo-io: a task graph to schedule and hash from, and state files that
 * make restored {@code target/} directories enough to continue a build.
 */
class ToolAgnosticIT {

    private static final String PROJECT = "basic-multimodule";

    /**
     * Simulates any tool that caches {@code target/}: build checkout A, copy only its target
     * directories into a fresh checkout B, and run just the next goal there. The recorded project state
     * (artifact files, generated source roots, JaCoCo's argLine) is replayed, relocated to B.
     */
    @Test
    void restoredTargetDirectoriesAreEnoughToContinueTheBuild() throws Exception {
        MavenBuild a = MavenBuild.checkout(PROJECT, "restore-target", "a");
        a.run("test-compile");
        assertTrue(Files.isRegularFile(a.dir().resolve(
                "lib-core/target/mojo-io/state/maven-compiler-plugin-compile-default-compile.state")));

        MavenBuild b = MavenBuild.checkout(PROJECT, "restore-target", "b");
        b.copyTargetsFrom(a, "lib-core", "app");
        MavenBuild.Result result = b.run("surefire:test");
        assertTrue(result.log().contains("[mojo-io] STATE org.jacoco:jacoco-maven-plugin"), result.log());
        assertFalse(result.log().contains("maven-compiler-plugin:3.13.0:compile ("), "compile must not run again");
        for (String module : List.of("lib-core", "app")) {
            assertTrue(Files.size(b.dir().resolve(module + "/target/jacoco.exec")) > 0,
                    "replayed argLine must point the agent at this checkout: " + module);
        }

        // Control: the same restore without state replay cannot continue the build.
        MavenBuild c = MavenBuild.checkout(PROJECT, "restore-target", "c");
        c.copyTargetsFrom(a, "lib-core", "app");
        MavenBuild.Result control = c.runAllowingFailure("surefire:test",
                "-Dmojoio.state.enabled=false");
        assertTrue(control.exitCode() != 0 || !Files.exists(c.dir().resolve("lib-core/target/jacoco.exec")),
                "without state replay the restored build should lose project state\n" + control.log());
    }

    /**
     * Restoring the packaged target/ directories is enough to install: the main artifact file and the
     * attached test and source jars all come from replayed state, no mojo before install runs.
     */
    @Test
    void restoredPackageInstallsWithAttachedArtifacts() throws Exception {
        MavenBuild a = MavenBuild.checkout(PROJECT, "restore-install", "a");
        a.run("package", "-DskipTests");

        Path installed = Path.of(System.getProperty("mojoio.it.localRepo"),
                "com/example/samples/lib-core/1.0.0-SNAPSHOT");
        MavenBuild.deleteTree(installed);
        MavenBuild b = MavenBuild.checkout(PROJECT, "restore-install", "b");
        b.copyTargetsFrom(a, "lib-core", "app");
        MavenBuild.Result result = b.run("install:install");
        assertFalse(result.log().contains("maven-jar-plugin:3.4.2:jar ("), "nothing is re-packaged");
        for (String suffix : List.of(".jar", "-tests.jar", "-sources.jar", ".pom")) {
            assertTrue(Files.isRegularFile(installed.resolve("lib-core-1.0.0-SNAPSHOT" + suffix)),
                    "installed lib-core" + suffix + "\n" + result.log());
        }
    }

    @Test
    void graphDescribesEveryExecutionWithoutRunningAny() throws Exception {
        MavenBuild build = MavenBuild.checkout(PROJECT, "graph", "checkout");
        build.run("verify", "-Dmojoio.graph.output=graph.json");

        assertFalse(Files.exists(build.dir().resolve("lib-core/target/classes")), "dry run must not build");
        JsonNode graph = new ObjectMapper().readTree(build.dir().resolve("graph.json").toFile());
        assertEquals("mojo-io-task-graph", graph.get("format").asText());
        assertEquals(1, graph.get("version").asInt());

        JsonNode app = project(graph, "com.example.samples:app");
        assertEquals("app", app.get("path").asText());
        assertTrue(texts(app.get("dependsOn")).contains("com.example.samples:lib-core"));
        assertTrue(texts(app.get("poms")).contains("{workspaceRoot}/pom.xml"));

        JsonNode compile = execution(app, "compile");
        assertTrue(compile.get("cacheable").asBoolean());
        assertEquals("built-in", compile.get("declaredBy").asText());
        JsonNode sources = compile.get("inputs").get(0);
        assertEquals("files", sources.get("kind").asText());
        assertEquals("{projectRoot}/src/main/java", sources.get("paths").get(0).asText());
        assertEquals("compile", compile.get("inputs").get(1).get("scope").asText());
        assertEquals("{projectRoot}/target/classes", compile.get("outputs").get(0).get("path").asText());
        assertEquals("{projectRoot}/target/mojo-io/state/maven-compiler-plugin-compile-default-compile.state",
                compile.get("stateFile").asText());

        assertEquals("carry", compile.get("stamping").asText());
        assertEquals("exact", execution(app, "test").get("stamping").asText());
        JsonNode jar = execution(app, "jar");
        assertTrue(jar.get("cacheable").asBoolean());
        assertEquals("{projectRoot}/target/app-1.0.0-SNAPSHOT.jar", jar.get("outputs").get(0).get("path").asText());
        assertEquals(build.dir().toAbsolutePath().normalize().toString(), graph.get("workspaceRoot").asText());
        assertNotEquals("", graph.get("maven").asText());
    }

    /** Declarations in the project's .mvn/plugin-io override the built-in ones. */
    @Test
    void projectDeclarationsOverrideBuiltins() throws Exception {
        MavenBuild build = MavenBuild.checkout(PROJECT, "override", "checkout");
        Path dir = Files.createDirectories(build.dir().resolve(".mvn/plugin-io"));
        Files.writeString(dir.resolve("surefire.xml"), """
                <plugin-io xmlns="urn:mojo-io:plugin-io:1.0">
                  <plugin groupId="org.apache.maven.plugins" artifactId="maven-surefire-plugin">
                    <mojo goal="test" cacheable="false" reason="integration tests hit a live service"/>
                  </plugin>
                </plugin-io>
                """);
        JsonNode app = project(TaskGraph.export(build, "test"), "com.example.samples:app");
        JsonNode test = execution(app, "test");
        assertFalse(test.get("cacheable").asBoolean(), test.toString());
        assertEquals("integration tests hit a live service", test.get("reason").asText());
        assertTrue(test.get("fingerprint").isNull());
        assertTrue(execution(app, "compile").get("cacheable").asBoolean());
    }

    /**
     * A build system restores outputs byte for byte, so the project version is part of the fingerprint
     * wherever it reaches the configuration or a value input: the jar's name, a filtered resource.
     */
    @Test
    void aVersionBumpChangesTheFingerprintsThatSeeTheVersion() throws Exception {
        MavenBuild build = MavenBuild.checkout(PROJECT, "version-bump", "checkout");
        JsonNode before = project(TaskGraph.export(build, "package"), "com.example.samples:lib-core");
        for (String module : List.of("", "lib-core/", "app/")) {
            Path pom = build.dir().resolve(module + "pom.xml");
            Files.writeString(pom, Files.readString(pom).replace("1.0.0-SNAPSHOT", "1.1.0-SNAPSHOT"));
        }
        JsonNode after = project(TaskGraph.export(build, "package"), "com.example.samples:lib-core");
        for (String goal : List.of("jar", "resources")) {
            assertNotEquals(execution(before, goal).get("fingerprint"), execution(after, goal).get("fingerprint"), goal);
        }
        assertEquals(execution(before, "compile").get("fingerprint"), execution(after, "compile").get("fingerprint"),
                "the compiler does not see the version");
    }

    private static JsonNode project(JsonNode graph, String id) {
        for (JsonNode p : graph.get("projects")) {
            if (p.get("id").asText().equals(id)) {
                return p;
            }
        }
        throw new AssertionError("no project " + id);
    }

    private static JsonNode execution(JsonNode project, String goal) {
        for (JsonNode e : project.get("executions")) {
            if (e.get("goal").asText().equals(goal)) {
                return e;
            }
        }
        throw new AssertionError("no execution " + goal);
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
