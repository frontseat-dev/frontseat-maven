package dev.frontseat.mojoio.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frontseat.mojoio.cache.CacheEntry;
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
        List<CacheEntry.StateRecord> diff = before.diff(ProjectState.capture(project), n);
        assertEquals(List.of(new CacheEntry.StateRecord("systemProperty", name, "@@BASEDIR@@/target/port.txt")), diff);

        System.clearProperty(name);
        ProjectState.apply(diff, project, n, null);
        assertEquals("/work/p/target/port.txt", System.getProperty(name));
        assertTrue(ProjectState.holds(diff.get(0), project, n));
        System.clearProperty(name);
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
