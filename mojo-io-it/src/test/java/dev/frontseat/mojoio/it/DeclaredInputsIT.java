package dev.frontseat.mojoio.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Inputs that are not parameters, as the task graph lists them: the project's own files
 * ({@code projectFiles}), its effective model ({@code projectModel}), the Maven version; and resources
 * written in the plugin configuration.
 */
class DeclaredInputsIT {

    private static final String ROOT = "declared-inputs";

    @Test
    void eachInputKindReachesTheGraph() throws Exception {
        MavenBuild build = MavenBuild.checkout(ROOT, "declared-inputs", "checkout");
        JsonNode graph = TaskGraph.export(build, "verify");
        JsonNode root = TaskGraph.project(graph, ROOT);

        // The project's files: its directory, without the build directory and nested modules.
        JsonNode files = TaskGraph.execution(root, "run").get("inputs").get(0);
        assertEquals("projectFiles", files.get("name").asText());
        assertEquals(List.of("{projectRoot}"), TaskGraph.texts(files.get("paths")));
        assertTrue(TaskGraph.texts(files.get("excludes")).containsAll(List.of("target/**", "sub/**")), files.toString());
        assertTrue(TaskGraph.execution(root, "run").get("outputs").get(0).get("observed").asBoolean());

        // A resource written in the plugin configuration.
        JsonNode copy = TaskGraph.execution(root, "copy-resources");
        assertEquals("{projectRoot}/src/extra", copy.get("inputs").get(0).get("paths").get(0).asText(), copy.toString());
        assertEquals("{projectRoot}/target/extra", copy.get("outputs").get(0).get("path").asText(), copy.toString());

        // The model: a property nobody's configuration uses still changes what a rule check may see.
        String enforce = TaskGraph.execution(root, "enforce").get("fingerprint").asText();
        String copied = copy.get("fingerprint").asText();
        Path pom = build.dir().resolve("pom.xml");
        Files.writeString(pom, Files.readString(pom).replace("<properties>", "<properties>\n    <added>1</added>"));
        JsonNode changed = TaskGraph.project(TaskGraph.export(build, "verify"), ROOT);
        assertNotEquals(enforce, TaskGraph.execution(changed, "enforce").get("fingerprint").asText());
        assertEquals(copied, TaskGraph.execution(changed, "copy-resources").get("fingerprint").asText());

        // An external dependency's version: in the project's externalDependencies, not in fingerprints.
        assertTrue(changed.get("externalDependencies").toString().contains("commons-lang3:jar:3.17.0:compile"),
                changed.get("externalDependencies").toString());
        Files.writeString(pom, Files.readString(pom).replace("<lang.version>3.17.0", "<lang.version>3.18.0"));
        JsonNode dependency = TaskGraph.project(TaskGraph.export(build, "verify"), ROOT);
        assertTrue(dependency.get("externalDependencies").toString().contains("commons-lang3:jar:3.18.0:compile"),
                dependency.get("externalDependencies").toString());
        assertEquals(copied, TaskGraph.execution(dependency, "copy-resources").get("fingerprint").asText());
    }
}
