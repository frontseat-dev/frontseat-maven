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
        MavenBuild build = MavenBuild.checkout("it-hooks", "m4-hooks", "checkout", MavenBuild.cacheDir("m4-hooks"));
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
        new ReferenceBuildSystem(build, MavenBuild.cacheDir("m4-hooks-bs")).build("verify");
        assertTrue(Files.isRegularFile(build.dir().resolve("target/failsafe-reports/TEST-sample.hooks.ServerIT.xml")));
    }

    /**
     * The concurrent builder calls mojo-io once per lifecycle step, so recorded state is replayed before
     * executions that then run anyway. Their own changes must still be recorded: a later hit restores them.
     */
    @Test
    void concurrentBuilderKeepsRecordedState() throws Exception {
        Path cache = MavenBuild.cacheDir("m4-concurrent");
        MavenBuild build = MavenBuild.checkout("basic-multimodule", "m4-concurrent", "a", cache);
        build.run("compile", "-b", "concurrent");
        Path greeter = build.dir().resolve("lib-core/src/main/java/sample/core/Greeter.java");
        Files.writeString(greeter, Files.readString(greeter).replace("\", \" + name", "\", dear \" + name"));

        MavenBuild.Result changed = build.run("compile", "-b", "concurrent");
        assertTrue(changed.log().contains("MISS  compiler:compile (default-compile) @ lib-core"), changed.log());
        assertTrue(changed.log().matches("(?s).*MISS  compiler:compile \\(default-compile\\) @ lib-core \\[\\w+] "
                + "stored 1 file\\(s\\), 2 state change\\(s\\).*"), "replayed changes merged back\n" + changed.log());

        MavenBuild fresh = MavenBuild.checkout("basic-multimodule", "m4-concurrent", "b", cache);
        Files.writeString(fresh.dir().resolve("lib-core/src/main/java/sample/core/Greeter.java"), Files.readString(greeter));
        MavenBuild.Result hit = fresh.run("compile", "-b", "concurrent");
        assertTrue(hit.log().matches("(?s).*HIT   compiler:compile \\(default-compile\\) @ lib-core \\[\\w+] "
                + "restored 1 file\\(s\\), 2 state change\\(s\\).*"), hit.log());
    }
}
