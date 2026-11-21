package dev.frontseat.mojoio.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Hooks around a phase (pre-/post-integration-test; Maven 4's before:/after:) hold process-local
 * state, so the graph marks them runTogether and a build system must run each bracket as one task.
 */
class HookBracketsIT {

    @Test
    void graphGroupsAPhaseWithItsHooks() throws Exception {
        MavenBuild build = MavenBuild.checkout("it-hooks", "hooks-graph", "checkout", MavenBuild.cacheDir("hooks-graph"));
        build.run("verify", "-Dmojoio.graph.output=graph.json");
        JsonNode project = new ObjectMapper().readTree(build.dir().resolve("graph.json").toFile()).get("projects").get(0);
        List<String> bracket = new ArrayList<>();
        for (JsonNode e : project.get("executions")) {
            if ("integration-test".equals(e.get("runTogether").asText(null))) {
                bracket.add(e.get("executionId").asText()); // phase names differ: Maven 4 reports before:/after:
            }
            if (e.get("goal").asText().equals("compile")) {
                assertTrue(e.get("runTogether").isNull(), "phases without hooks are not grouped");
            }
        }
        assertEquals(List.of("start-server", "default", "stop-server"), bracket);
    }

    @Test
    void buildSystemRunsTheBracketAsOneTask() throws Exception {
        Path cache = MavenBuild.cacheDir("hooks-run");
        MavenBuild build = MavenBuild.checkout("it-hooks", "hooks-run", "checkout", cache);
        List<ReferenceBuildSystem.Outcome> outcomes =
                new ReferenceBuildSystem(build, MavenBuild.cacheDir("hooks-run-bs")).build("verify");
        assertTrue(outcomes.stream().anyMatch(o -> o.task().endsWith(":integration-test")), outcomes.toString());
        assertTrue(Files.isRegularFile(build.dir().resolve("target/failsafe-reports/TEST-sample.hooks.ServerIT.xml")));

        // Control: splitting the bracket into per-phase tasks loses the "server" between processes.
        // Not under mvnd: there the "process" is the daemon, and the server outlives each invocation.
        if (MavenBuild.daemon()) {
            return;
        }
        MavenBuild split = MavenBuild.checkout("it-hooks", "hooks-run", "split", cache);
        ReferenceBuildSystem.TaskFailed failed = assertThrows(ReferenceBuildSystem.TaskFailed.class,
                () -> new ReferenceBuildSystem(split, MavenBuild.cacheDir("hooks-split-bs"), false).build("verify"));
        assertTrue(failed.task.endsWith(":integration-test") || failed.task.endsWith(":verify"), failed.task);
    }
}
