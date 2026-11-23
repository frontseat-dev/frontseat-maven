package dev.frontseat.mojoio.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frontseat.mojoio.extension.descriptor.ResolvedInput;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DryInputCheckTest {

    private static final Path BASE = Path.of("/work/app").toAbsolutePath();
    private static final List<Path> EXEMPT = List.of(BASE.resolve("target"), Path.of("/m2").toAbsolutePath());

    private static ResolvedInput files(String name, Path... paths) {
        return ResolvedInput.files(name, List.of(paths));
    }

    @Test
    void sameInputsAreCovered() {
        ResolvedInput roots = files("compileSourceRoots", BASE.resolve("src/main/java"));
        assertTrue(DryInputCheck.uncovered(List.of(roots), List.of(roots), EXEMPT).isEmpty());
    }

    @Test
    void anAddedRootOutsideTheBuildDirectoryIsReported() {
        List<DryInputCheck.Finding> findings = DryInputCheck.uncovered(
                List.of(files("compileSourceRoots", BASE.resolve("src/main/java"), BASE.resolve("src/other"))),
                List.of(files("compileSourceRoots", BASE.resolve("src/main/java"))), EXEMPT);
        assertEquals(List.of(new DryInputCheck.Finding("compileSourceRoots", BASE.resolve("src/other"))), findings);
    }

    /** Tycho: build.properties replaces Maven's default root with another, broader one. */
    @Test
    void aReplacedRootIsReportedEvenWhenItContainsTheDryOne() {
        List<DryInputCheck.Finding> findings = DryInputCheck.uncovered(
                List.of(files("compileSourceRoots", BASE.resolve("src"))),
                List.of(files("compileSourceRoots", BASE.resolve("src/main/java"))), EXEMPT);
        assertEquals(1, findings.size());
        assertEquals(BASE.resolve("src"), findings.get(0).path());
    }

    @Test
    void rootsUnderTheBuildDirectoryOrTheLocalRepositoryAreExempt() {
        assertTrue(DryInputCheck.uncovered(
                List.of(files("compileSourceRoots", BASE.resolve("target/generated-sources/annotations"),
                        Path.of("/m2/org/x/1/x-1.jar").toAbsolutePath())),
                List.of(), EXEMPT).isEmpty());
    }

    @Test
    void aBroaderDryInputCoversAnyPathUnderIt() {
        assertTrue(DryInputCheck.uncovered(
                List.of(files("compileSourceRoots", BASE.resolve("src"))),
                List.of(ResolvedInput.files("projectFiles", BASE, List.of(), List.of("target/**"))), EXEMPT).isEmpty());
    }

    @Test
    void messageNamesTheMojoThePathAndTheFix() {
        String message = DryInputCheck.message("compiler:compile (default-compile) @ app", "built-in",
                List.of(new DryInputCheck.Finding("compileSourceRoots", BASE.resolve("src/other"))), BASE);
        assertTrue(message.startsWith("compiler:compile (default-compile) @ app reads a path the task graph does not "
                + "list: src/other (input 'compileSourceRoots')"), message);
        assertTrue(message.contains("<projectFiles/>"), message);
    }
}
