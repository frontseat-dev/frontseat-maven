package dev.frontseat.mojoio.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frontseat.mojoio.spec.StateChange;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

class ProjectStateTest {

    private final PathNormalizer n = new PathNormalizer(Path.of("/work/p"), Path.of("/work"), Path.of("/m2"));

    private static MavenProject project() {
        MavenProject p = new MavenProject(new Model());
        p.setFile(new File("/work/p/pom.xml"));
        return p;
    }

    /** A hook that hands data to later mojos through a system property (e.g. properties-maven-plugin). */
    @Test
    void systemPropertyHandoffsAreRecordedAndReplayed() throws Exception {
        String name = "mojoio.test.handoff";
        System.clearProperty(name);
        MavenProject project = project();
        ProjectState before = ProjectState.capture(project);
        System.setProperty(name, "/work/p/target/port.txt");
        List<StateChange> diff = before.diff(ProjectState.capture(project), n);
        assertEquals(List.of(new StateChange("systemProperty", name, "@@BASEDIR@@/target/port.txt")), diff);

        System.clearProperty(name);
        ProjectState.apply(diff, project, n, null);
        assertEquals("/work/p/target/port.txt", System.getProperty(name));
        assertTrue(ProjectState.holds(diff.get(0), project, n));
        System.clearProperty(name);
    }

    /** Holds a context of its own, as Eclipse Tycho's ReactorProject does. */
    public static final class ContextOwner {
        private final java.util.Map<String, Object> context = new java.util.HashMap<>();

        public void setContextValue(String key, Object value) {
            context.put(key, value);
        }
    }

    /** Mojos that hand text to later ones through the project context, directly or nested (Tycho). */
    @Test
    void contextValueHandoffsAreRecordedAndReplayed() throws Exception {
        MavenProject project = project();
        ContextOwner owner = new ContextOwner();
        project.setContextValue("tycho.reactor-project.12345", owner);
        ProjectState before = ProjectState.capture(project, false);
        project.setContextValue("plain", "/work/p/target/x");
        owner.setContextValue("DeclarativeServicesMojoHeader", "OSGI-INF/a.xml");
        owner.setContextValue("someObject", new Object()); // not text: not recorded
        List<StateChange> diff = before.diff(ProjectState.capture(project, false), n);
        String nested = "@" + ContextOwner.class.getName() + "#DeclarativeServicesMojoHeader";
        assertEquals(List.of(
                new StateChange("contextValue", nested, "OSGI-INF/a.xml"),
                new StateChange("contextValue", "plain", "@@BASEDIR@@/target/x")), diff);

        // Another JVM: the owner is under another key; the record names it by class.
        MavenProject replayed = project();
        ContextOwner other = new ContextOwner();
        replayed.setContextValue("tycho.reactor-project.67890", other);
        ProjectState.apply(diff, replayed, n, null);
        assertEquals("/work/p/target/x", replayed.getContextValue("plain"));
        assertEquals("OSGI-INF/a.xml", other.context.get("DeclarativeServicesMojoHeader"));
        assertTrue(diff.stream().allMatch(r -> ProjectState.holds(r, replayed, n)));
    }

    @Test
    void jvmPropertiesAreNotState() throws Exception {
        MavenProject project = project();
        ProjectState before = ProjectState.capture(project);
        String old = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", "/elsewhere");
        try {
            assertTrue(before.diff(ProjectState.capture(project), n).isEmpty());
        } finally {
            System.setProperty("java.io.tmpdir", old);
        }
        assertNull(System.getProperty("mojoio.test.none"));
    }
}
