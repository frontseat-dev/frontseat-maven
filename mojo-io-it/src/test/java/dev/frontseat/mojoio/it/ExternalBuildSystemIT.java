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
    }
}
