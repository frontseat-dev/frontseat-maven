package dev.frontseat.mojoio.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code mojoio.only.inputCheck}: the task graph is exported in a dry run, so a source root a mojo adds
 * while the build runs is invisible to it. When a build system runs a task, mojo-io compares what each
 * declared mojo reads with what the graph listed, and fails the task when a path outside the build
 * directories is missing (see docs/task-graph.md).
 */
class DryInputCheckIT {

    private static final String PROJECT = "source-roots";
    private static final String NO_CACHE = "-Dmojoio.cache.enabled=false";
    private static final String OTHER = "com.example.samples:other-root";
    private static final String GENERATED = "com.example.samples:generated-root";
    private static final String COMPILE_LABEL = "compiler:compile (default-compile) @ other-root";

    /**
     * Task by task, as a build system runs the graph: build-helper's add-source runs in the
     * generate-sources task, and its state (the src/other root) is replayed into the compile task. The
     * compiler would read src/other, which the graph never listed: the compile task fails. The project
     * whose added root is under target/ builds normally.
     */
    @Test
    void taskByTaskASourceRootOutsideTheBuildDirectoryFailsItsTask() throws Exception {
        Path cache = MavenBuild.cacheDir("dry-inputs-tasks");
        MavenBuild build = MavenBuild.checkout(PROJECT, "dry-inputs-tasks", "checkout", cache);
        ReferenceBuildSystem bs = new ReferenceBuildSystem(build, MavenBuild.cacheDir("dry-inputs-tasks-bs"), "phase");

        ReferenceBuildSystem.TaskFailed failed = assertThrows(ReferenceBuildSystem.TaskFailed.class,
                () -> bs.build("compile"));
        assertEquals(OTHER + ":compile", failed.task, failed.getMessage());
        String log = failed.getMessage();
        assertTrue(log.contains("[mojo-io] INPUTS " + COMPILE_LABEL + " reads a path the task graph does not list: "
                + "src/other (input 'compileSourceRoots')"), log);
        assertTrue(log.contains("<projectFiles/>"), "the message names the fix\n" + log);
        assertTrue(log.contains("-Dmojoio.only.inputCheck=warn"), "and the escape hatch\n" + log);
        assertTrue(log.contains("org.codehaus.mojo:build-helper-maven-plugin:3.6.1:add-source@add-other @ other-root replayed"),
                "the root came from replayed state\n" + log);

        // Generated sources under target/ are fine: generated-root's tasks all ran before.
        assertTrue(Files.isRegularFile(build.dir().resolve("generated-root/target/classes/sample/gen/Generated.class")));
        assertTrue(Files.isRegularFile(build.dir().resolve("generated-root/target/classes/sample/gen/Main.class")));
    }

    /**
     * One task holding the whole compile stage: the root is added by an earlier mojo of the same Maven
     * invocation, not by replay. Same verdict. {@code warn} lets the task run and says why it is unsafe.
     */
    @Test
    void anEarlierMojoOfTheSameTaskIsCaughtAndWarnModeContinues() throws Exception {
        Path cache = MavenBuild.cacheDir("dry-inputs-stage");
        MavenBuild build = MavenBuild.checkout(PROJECT, "dry-inputs-stage", "checkout", cache);

        MavenBuild.Result generated = build.run("-pl", GENERATED, "-am", "-Dmojoio.only=" + GENERATED, NO_CACHE,
                "compile");
        assertFalse(generated.log().contains("[mojo-io] INPUTS"), generated.log());
        assertTrue(Files.isRegularFile(build.dir().resolve("generated-root/target/classes/sample/gen/Generated.class")));

        MavenBuild.Result failed = build.runAllowingFailure("-pl", OTHER, "-am", "-Dmojoio.only=" + OTHER, NO_CACHE,
                "compile");
        assertNotEquals(0, failed.exitCode(), failed.log());
        assertTrue(failed.log().contains("[mojo-io] INPUTS " + COMPILE_LABEL), failed.log());
        assertFalse(Files.exists(build.dir().resolve("other-root/target/classes/sample/other/Main.class")),
                "the guard stops the mojo before it runs");

        MavenBuild.Result warned = build.run("-pl", OTHER, "-am", "-Dmojoio.only=" + OTHER, NO_CACHE,
                "-Dmojoio.only.inputCheck=warn", "compile");
        assertTrue(warned.log().contains("[WARNING] [mojo-io] INPUTS " + COMPILE_LABEL), warned.log());
        assertTrue(Files.isRegularFile(build.dir().resolve("other-root/target/classes/sample/other/Other.class")));

        MavenBuild.Result off = build.run("-pl", OTHER, "-am", "-Dmojoio.only=" + OTHER, NO_CACHE,
                "-Dmojoio.only.inputCheck=off", "compile");
        assertFalse(off.log().contains("[mojo-io] INPUTS"), off.log());
    }

    /** A plain build is not a build system's task: no graph to disagree with, no check. */
    @Test
    void aPlainBuildIsNotChecked() throws Exception {
        Path cache = MavenBuild.cacheDir("dry-inputs-plain");
        MavenBuild build = MavenBuild.checkout(PROJECT, "dry-inputs-plain", "checkout", cache);
        MavenBuild.Result result = build.run("compile");
        assertFalse(result.log().contains("[mojo-io] INPUTS"), result.log());
        assertTrue(Files.isRegularFile(build.dir().resolve("other-root/target/classes/sample/other/Other.class")));
    }
}
