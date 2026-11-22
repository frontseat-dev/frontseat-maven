package dev.frontseat.mojoio.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * mojo-io as the contract for an external build system's cache (the Nx use case): the reference build
 * system caches Maven tasks with its own hashing and storage, using only the task graph, state files
 * and single-task invocations. mojo-io's own cache is never used.
 */
class ExternalBuildSystemIT {

    @Test
    void externalBuildSystemCachesMavenTasksFromTheGraphAlone() throws Exception {
        Path mojoioCache = MavenBuild.cacheDir("external");
        Path buildSystemCache = MavenBuild.cacheDir("external-bs");

        // Cold: every task runs.
        MavenBuild a = MavenBuild.checkout("basic-multimodule", "external", "a", mojoioCache);
        List<ReferenceBuildSystem.Outcome> cold = new ReferenceBuildSystem(a, buildSystemCache).build("test");
        assertTrue(cold.size() >= 13, cold.toString());
        assertTrue(cold.stream().noneMatch(ReferenceBuildSystem.Outcome::hit), cold.toString());

        // Fresh checkout elsewhere: everything comes from the build system's cache, nothing runs.
        MavenBuild b = MavenBuild.checkout("basic-multimodule", "external", "b", mojoioCache);
        ReferenceBuildSystem bs = new ReferenceBuildSystem(b, buildSystemCache);
        List<ReferenceBuildSystem.Outcome> warm = bs.build("test");
        assertTrue(warm.stream().allMatch(ReferenceBuildSystem.Outcome::hit), warm.toString());
        assertTrue(Files.isRegularFile(b.dir().resolve("lib-core/target/classes/sample/core/Greeter.class")));
        assertTrue(Files.isRegularFile(b.dir().resolve("app/target/surefire-reports/TEST-sample.app.AppTest.xml")));
        // Written by JaCoCo's agent inside the test JVM: an observed output, listed in the state file.
        assertTrue(Files.isRegularFile(b.dir().resolve("lib-core/target/jacoco.exec")), "observed output restored");

        // Change one test: only that project's test-compile and test run, against restored upstream
        // outputs and replayed state (lib-core's artifact file, JaCoCo's argLine relocated to b).
        Path test = b.dir().resolve("app/src/test/java/sample/app/AppTest.java");
        Files.writeString(test, Files.readString(test).replace("class AppTest {", """
                class AppTest {

                    @Test
                    void greetsSomeoneElse() {
                        assertEquals("Hello, b!", App.run("b"));
                    }
                """));
        List<ReferenceBuildSystem.Outcome> incremental = bs.build("test");
        List<String> missed = incremental.stream().filter(o -> !o.hit()).map(ReferenceBuildSystem.Outcome::task)
                .collect(Collectors.toList());
        assertEquals(List.of("com.example.samples:app:test-compile", "com.example.samples:app:test"), missed,
                incremental.toString());
        assertTrue(Files.readString(b.dir().resolve("app/target/surefire-reports/TEST-sample.app.AppTest.xml"))
                .contains("tests=\"3\""));
        assertTrue(Files.size(b.dir().resolve("app/target/jacoco.exec")) > 0, "agent attached via replayed state");
        assertTrue(Files.readString(b.dir().resolve("lib-core/target/surefire-reports/TEST-sample.core.GreeterTest.xml"))
                .contains("tests=\"2\""), "lib-core results restored, not re-run");

        // Maven 4 phase links: app's tasks depend on lib-core's "ready" (compile + resources), not on its
        // tests. Changing a lib-core test re-runs lib-core's test tasks only; every app task stays a hit.
        Path libTest = b.dir().resolve("lib-core/src/test/java/sample/core/GreeterTest.java");
        Files.writeString(libTest, Files.readString(libTest).replace("class GreeterTest {", """
                class GreeterTest {

                    @Test
                    void greetsTheWorld() {
                        assertEquals("Hello, world!", new Greeter().greet("world"));
                    }
                """));
        List<String> missedByLibTest = bs.build("test").stream().filter(o -> !o.hit())
                .map(ReferenceBuildSystem.Outcome::task).collect(Collectors.toList());
        assertEquals(List.of("com.example.samples:lib-core:test-compile", "com.example.samples:lib-core:test"),
                missedByLibTest);
    }

    /**
     * Stage granularity (the default): one task per project and stage, so a build system starts Maven
     * half as often. Downstream projects still depend only on the upstream compile stage.
     */
    @Test
    void stagesJoinPhasesAndKeepPhaseLinks() throws Exception {
        Path buildSystemCache = MavenBuild.cacheDir("external-stage-bs");
        Path mojoioCache = MavenBuild.cacheDir("external-stage-unused");
        MavenBuild a = MavenBuild.checkout("basic-multimodule", "external-stage", "a", mojoioCache);
        List<ReferenceBuildSystem.Outcome> cold = new ReferenceBuildSystem(a, buildSystemCache, "stage").build("verify");
        List<String> names = cold.stream().map(ReferenceBuildSystem.Outcome::task).collect(Collectors.toList());
        for (String project : List.of("lib-core", "app")) {
            assertEquals(List.of("compile", "test", "package"), names.stream()
                    .filter(n -> n.startsWith("com.example.samples:" + project + ":"))
                    .map(n -> n.substring(n.lastIndexOf(':') + 1)).filter(n -> !n.equals("verify"))
                    .collect(Collectors.toList()), names.toString());
        }

        MavenBuild b = MavenBuild.checkout("basic-multimodule", "external-stage", "b", mojoioCache);
        ReferenceBuildSystem bs = new ReferenceBuildSystem(b, buildSystemCache, "stage");
        assertTrue(bs.build("verify").stream().allMatch(ReferenceBuildSystem.Outcome::hit));
        assertTrue(Files.isRegularFile(b.dir().resolve("app/target/app-1.0.0-SNAPSHOT.jar")));

        Path libTest = b.dir().resolve("lib-core/src/test/java/sample/core/GreeterTest.java");
        Files.writeString(libTest, Files.readString(libTest).replace("class GreeterTest {", """
                class GreeterTest {

                    @Test
                    void greetsTheWorld() {
                        assertEquals("Hello, world!", new Greeter().greet("world"));
                    }
                """));
        List<String> missed = bs.build("verify").stream().filter(o -> !o.hit())
                .map(ReferenceBuildSystem.Outcome::task).filter(t -> !t.endsWith(":package") && !t.endsWith(":verify"))
                .collect(Collectors.toList());
        assertEquals(List.of("com.example.samples:lib-core:test"), missed);
    }

    /**
     * Ordering that Maven 4's phase links alone do not give. b depends on c in test scope only and
     * resolves the test classpath in its compile stage; b also depends on a's test-jar, which only
     * exists once a is packaged; and c has a runtime dependency (d) that b's tests need although c's
     * compile does not. The graph's dependsOn must say all three; the project must also build task
     * by task.
     */
    @Test
    void tasksWaitForWhatTheirMojosResolveAndForAttachedArtifacts() throws Exception {
        Path buildSystemCache = MavenBuild.cacheDir("ordering-bs");
        MavenBuild build = MavenBuild.checkout("task-ordering", "ordering", "checkout", MavenBuild.cacheDir("ordering-unused"));
        build.run("verify", "-Dmojoio.graph.output=graph.json");
        com.fasterxml.jackson.databind.JsonNode graph = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(build.dir().resolve("graph.json").toFile());
        List<String> compileDependsOn = new java.util.ArrayList<>();
        List<String> testDependsOn = new java.util.ArrayList<>();
        List<String> validateDependsOn = new java.util.ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode p : graph.get("projects")) {
            if (p.get("id").asText().equals("com.example.samples:b")) {
                for (com.fasterxml.jackson.databind.JsonNode t : p.get("tasks")) {
                    if (t.get("name").asText().equals("compile")) {
                        t.get("dependsOn").forEach(d -> compileDependsOn.add(d.asText()));
                    }
                    if (t.get("name").asText().equals("validate")) {
                        t.get("dependsOn").forEach(d -> validateDependsOn.add(d.asText()));
                    }
                    if (t.get("name").asText().equals("test")) {
                        t.get("dependsOn").forEach(d -> testDependsOn.add(d.asText()));
                    }
                }
            }
        }
        assertTrue(compileDependsOn.contains("com.example.samples:c:compile"),
                "a test-scope dependency a mojo of the stage resolves: " + compileDependsOn);
        assertTrue(compileDependsOn.contains("com.example.samples:a:package"),
                "a test-jar needs the upstream project packaged: " + compileDependsOn);

        // antrun runs in validate, next to dependency:build-classpath, which can't be cached: those
        // get a task of their own before the compile stage.
        assertTrue(validateDependsOn.contains("com.example.samples:tools:package"),
                "a plugin dependency built in this reactor has to be packaged: " + validateDependsOn);
        assertTrue(compileDependsOn.contains("com.example.samples:b:validate"), compileDependsOn.toString());
        assertTrue(testDependsOn.contains("com.example.samples:d:compile"),
                "the runtime dependency of a dependency is there when tests run: " + testDependsOn);

        // c names tools only in plugin configuration: its tasks wait for tools, and the build system
        // is told to select it (-pl), as Maven's -am would not.
        for (com.fasterxml.jackson.databind.JsonNode p : graph.get("projects")) {
            if (p.get("id").asText().equals("com.example.samples:c")) {
                assertEquals("[\"com.example.samples:tools\"]", p.get("configurationReferences").toString());
                List<String> refs = new java.util.ArrayList<>();
                p.get("reactorReferences").forEach(r -> refs.add(r.asText()));
                assertTrue(refs.containsAll(List.of("com.example.samples:tools", "com.example.samples:d")), refs.toString());
                for (com.fasterxml.jackson.databind.JsonNode t : p.get("tasks")) {
                    List<String> deps = new java.util.ArrayList<>();
                    t.get("dependsOn").forEach(d -> deps.add(d.asText()));
                    assertTrue(deps.stream().anyMatch(d -> d.startsWith("com.example.samples:tools:")), t + "");
                }
            }
        }

        List<ReferenceBuildSystem.Outcome> cold = new ReferenceBuildSystem(build, buildSystemCache, "stage").build("verify");
        assertTrue(cold.stream().noneMatch(ReferenceBuildSystem.Outcome::hit), cold.toString());
        assertTrue(Files.readString(build.dir().resolve("b/target/surefire-reports/TEST-sample.b.MainBTest.xml"))
                .contains("tests=\"1\""));
    }

    /**
     * A plugin dependency built in the same reactor (a build-tools module) is an input of the mojo by
     * content, not just by coordinates. And resolving dependencies for a cache key must not narrow
     * what Maven resolved for the build: a mojo without a declaration, after a declared one that needs
     * less, still gets the whole test classpath.
     */
    @Test
    void reactorPluginDependenciesAndDependencyResolutionStayAsInPlainMaven() throws Exception {
        Path cache = MavenBuild.cacheDir("plugin-deps");
        MavenBuild build = MavenBuild.checkout("task-ordering", "plugin-deps", "checkout", cache);
        build.run("verify");
        assertEquals("tool v1", Files.readString(build.dir().resolve("b/target/tool-copy.txt")).trim());
        String afterCompile = Files.readString(build.dir().resolve("b/target/test-classpath-after-compile.txt"));
        assertTrue(afterCompile.contains("junit-jupiter"), "test-scope dependencies after a compile-scope mojo: " + afterCompile);

        MavenBuild.Result warm = build.run("clean", "verify");
        assertEquals("HIT", warm.outcome("b", "antrun:run"), warm.log());
        assertTrue(Files.readString(build.dir().resolve("b/target/test-classpath-after-compile.txt")).contains("junit-jupiter"),
                "the same when the compile mojo was restored");

        Files.writeString(build.dir().resolve("tools/src/main/resources/tool.txt"), "tool v2\n");
        MavenBuild.Result changed = build.run("verify");
        assertEquals("MISS", changed.outcome("b", "antrun:run"), changed.log());
        assertEquals("tool v2", Files.readString(build.dir().resolve("b/target/tool-copy.txt")).trim());
    }
}
