package dev.frontseat.mojoio.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import dev.frontseat.mojoio.spec.StateChange;
import dev.frontseat.mojoio.spec.StateFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Maven 4's lifecycle hooks: before:/after: on every phase (Maven 3's pre-/post-/generate-/process-
 * phases become aliases of them) and the concurrent builder, which runs a project's lifecycle in steps.
 * Only meaningful when the ITs fork Maven 4 (mise run it:maven4).
 */
class Maven4HooksIT {

    @BeforeEach
    void maven4Only() {
        assumeTrue(System.getProperty("mojoio.it.expectMavenVersion", "").startsWith("4"), "needs Maven 4");
    }

    @Test
    void nativeBeforeAfterHooksFormOneTaskWithCanonicalNames() throws Exception {
        MavenBuild build = MavenBuild.checkout("it-hooks", "m4-hooks", "checkout");
        Path pom = build.dir().resolve("pom.xml");
        Files.writeString(pom, Files.readString(pom)
                .replace("<phase>pre-integration-test</phase>", "<phase>before:integration-test</phase>")
                .replace("<phase>post-integration-test</phase>", "<phase>after:integration-test</phase>"));

        build.run("verify", "-Dmojoio.graph.output=graph.json");
        JsonNode executions = new ObjectMapper().readTree(build.dir().resolve("graph.json").toFile())
                .get("projects").get(0).get("executions");
        List<String> bracket = new ArrayList<>();
        for (JsonNode e : executions) {
            if ("integration-test".equals(e.get("runTogether").asText(null))) {
                bracket.add(e.get("phase").asText() + "=" + e.get("canonicalPhase").asText());
            }
            if (e.get("goal").asText().equals("resources")) {
                assertEquals("after:resources", e.get("phase").asText());
                assertEquals("process-resources", e.get("canonicalPhase").asText());
                assertTrue(e.get("runTogether").isNull(), "a hook alone is not a group");
            }
        }
        assertEquals(List.of("before:integration-test=pre-integration-test", "integration-test=integration-test",
                "after:integration-test=post-integration-test"), bracket);

        // And a build system honouring runTogether runs the bracket (server in the Maven JVM) in one task.
        new ReferenceBuildSystem(build, MavenBuild.emptyDir("m4-hooks", "bs")).build("verify");
        assertTrue(Files.isRegularFile(build.dir().resolve("target/failsafe-reports/TEST-sample.hooks.ServerIT.xml")));
    }

    /**
     * The concurrent builder calls mojo-io once per lifecycle step, so recorded state is replayed before
     * executions that then run anyway. Their own changes must still be recorded.
     */
    @Test
    void concurrentBuilderKeepsRecordedState() throws Exception {
        MavenBuild build = MavenBuild.checkout("basic-multimodule", "m4-concurrent", "a");
        build.run("compile", "-b", "concurrent");
        Path state = build.dir().resolve("lib-core/target/mojo-io/state/maven-compiler-plugin-compile-default-compile.state");
        List<StateChange> recorded = StateFile.read(state).changes();
        assertEquals(2, recorded.size(), recorded.toString());

        Path greeter = build.dir().resolve("lib-core/src/main/java/sample/core/Greeter.java");
        Files.writeString(greeter, Files.readString(greeter).replace("\", \" + name", "\", dear \" + name"));
        build.run("compile", "-b", "concurrent");
        assertEquals(recorded, StateFile.read(state).changes(), "replayed changes merged back");
    }
}
