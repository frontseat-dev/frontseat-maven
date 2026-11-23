package dev.frontseat.mojoio.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frontseat.mojoio.extension.descriptor.ResolvedInput;
import dev.frontseat.mojoio.extension.descriptor.ResolvedOutput;
import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

class AuditTest {

    private static final Path WS = Path.of("/tmp/ws");

    private static MavenProject project() {
        Model model = new Model();
        model.setGroupId("g");
        model.setArtifactId("app");
        Build build = new Build();
        build.setDirectory("/tmp/ws/app/target");
        model.setBuild(build);
        MavenProject p = new MavenProject(model);
        p.setFile(new File("/tmp/ws/app/pom.xml"));
        return p;
    }

    private static Audit.Coverage coverage() {
        return new Audit.Coverage(
                List.of(ResolvedInput.files("sources", Path.of("/tmp/ws/app/src"), List.of(), List.of("**/*.bak")),
                        ResolvedInput.classpath("classpath", List.of(Path.of("/tmp/ws/lib/target/lib.jar")), "compile")),
                List.of(new ResolvedOutput("classes", Path.of("/tmp/ws/app/target/classes"), List.of(), List.of())),
                List.of("maven-status/**"), "built-in", true, null);
    }

    @Test
    void readsAreCoveredByFilesClasspathsAndOwnOutputs() {
        Audit.Coverage c = coverage();
        assertTrue(Audit.readCovered(Path.of("/tmp/ws/app/src/A.java"), c));
        assertFalse(Audit.readCovered(Path.of("/tmp/ws/app/src/A.bak"), c), "excluded");
        assertTrue(Audit.readCovered(Path.of("/tmp/ws/lib/target/lib.jar"), c));
        assertTrue(Audit.readCovered(Path.of("/tmp/ws/app/target/classes/A.class"), c), "its own earlier output");
        assertFalse(Audit.readCovered(Path.of("/tmp/ws/app/notes.txt"), c));
    }

    @Test
    void findsWhatTheDeclarationMisses(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        // The workspace lies inside an ignored directory (the temporary directory) and is still audited.
        Path ws = tmp.resolve("ws");
        Path app = ws.resolve("app");
        Model model = new Model();
        model.setGroupId("g");
        model.setArtifactId("app");
        Build build = new Build();
        build.setDirectory(app.resolve("target").toString());
        model.setBuild(build);
        MavenProject project = new MavenProject(model);
        project.setFile(app.resolve("pom.xml").toFile());
        java.nio.file.Files.createDirectories(app.resolve("target"));
        java.nio.file.Files.writeString(app.resolve("target/report.txt"), "kept");
        Audit.Coverage c = new Audit.Coverage(List.of(ResolvedInput.files("sources", app.resolve("src"), List.of(), List.of())),
                List.of(new ResolvedOutput("classes", app.resolve("target/classes"), List.of(), List.of())),
                List.of("maven-status/**"), "built-in", true, null);
        Audit.Window w = new Audit.Window("compiler:compile @ app", project, c);
        Instant t = Instant.now();
        w.accesses.add(new Audit.Access(t, 1, 'r', app.resolve("src/A.java").toString()));
        w.accesses.add(new Audit.Access(t, 1, 'r', app.resolve("notes.txt").toString()));
        w.accesses.add(new Audit.Access(t, 1, 'r', app.resolve("target/maven-archiver/pom.properties").toString()));
        w.accesses.add(new Audit.Access(t, 1, 'r', ws.resolve("pom.xml").toString(), List.of("org.apache.maven.impl.model.X")));
        w.accesses.add(new Audit.Access(t, 1, 'r', tmp.resolve("other/cache.bin").toString()));
        w.accesses.add(new Audit.Access(t, 1, 'w', app.resolve("target/classes/A.class").toString()));
        w.accesses.add(new Audit.Access(t, 1, 'w', app.resolve("target/maven-status/x.lst").toString()));
        w.accesses.add(new Audit.Access(t, 1, 'w', app.resolve("target/report.txt").toString()));
        w.accesses.add(new Audit.Access(t, 1, 'w', app.resolve("target/jartmp1.tmp").toString()));
        w.accesses.add(new Audit.Access(t, 1, 'r', app.resolve("target/report.txt").toString()));
        w.accesses.add(new Audit.Access(t, 1, 'p', "node build.js"));
        w.accesses.add(new Audit.Access(t, 1, 'p', "java -XX:StartFlightRecording:settings=x -jar booter.jar"));

        Audit.Finding f = Audit.analyze(w, List.of(tmp), ws);

        assertEquals(List.of("notes.txt"), f.undeclaredReads(), "Maven's model read, temp files and bookkeeping don't count");
        assertEquals(List.of("target/report.txt"), f.undeclaredWrites(), "jartmp1.tmp was deleted again: temporary");
        assertEquals(List.of("node build.js"), f.unobservedProcesses(), "an observed test JVM isn't reported");
        assertTrue(f.problem());
    }

    @Test
    void aMojoReadingPomsThroughMavensApiStillReadsThem() {
        Audit.Window w = new Audit.Window("flatten:flatten @ app", project(), coverage());
        w.mojoPackage = "org.codehaus.mojo.flatten.";
        w.accesses.add(new Audit.Access(Instant.now(), 1, 'r', "/tmp/ws/app/pom.xml",
                List.of("org.apache.maven.impl.model.X", "org.codehaus.mojo.flatten.FlattenMojo")));
        assertEquals(List.of("pom.xml"), Audit.analyze(w, List.of(), WS).undeclaredReads());
    }
}
