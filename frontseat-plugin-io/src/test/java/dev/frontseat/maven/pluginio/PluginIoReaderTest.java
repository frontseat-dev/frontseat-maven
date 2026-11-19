package dev.frontseat.maven.pluginio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginIoReaderTest {

    private static final String DOC = """
            <plugin-io xmlns="https://frontseat.dev/xsd/plugin-io/1.0">
              <plugin groupId="org.example" artifactId="gen-maven-plugin">
                <mojo goal="generate" prerequisitePhase="initialize">
                  <inputs>
                    <files parameter="schemaDirectory"/>
                    <classpath parameter="pluginClasspath" excludeParameter="outputDirectory"/>
                  </inputs>
                  <outputs>
                    <directory parameter="outputDirectory" includes="**/*.java"/>
                  </outputs>
                  <ignoredParameters>verbose, quiet
                    logLevel</ignoredParameters>
                </mojo>
                <mojo goal="publish" cacheable="false" reason="talks to a server"/>
              </plugin>
            </plugin-io>
            """;

    @Test
    void readsMojosInputsOutputsAndIgnoredParameters() {
        PluginIo plugin = read(DOC).get(0);
        assertEquals("org.example:gen-maven-plugin", plugin.key());

        MojoIo generate = plugin.mojo("generate").orElseThrow();
        assertTrue(generate.cacheable());
        assertEquals("initialize", generate.prerequisitePhase());
        assertEquals(List.of("files", "classpath"), generate.inputs().stream().map(IoDeclaration::kind).toList());
        assertEquals("outputDirectory", generate.inputs().get(1).required("excludeParameter"));
        assertEquals("**/*.java", generate.outputs().get(0).required("includes"));
        assertEquals(Set.of("verbose", "quiet", "logLevel"), generate.ignoredParameters());

        MojoIo publish = plugin.mojo("publish").orElseThrow();
        assertFalse(publish.cacheable());
        assertEquals("talks to a server", publish.reason());
    }

    @Test
    void notCacheableRequiresAReason() {
        String doc = DOC.replace(" reason=\"talks to a server\"", "");
        assertThrows(PluginIoException.class, () -> read(doc));
    }

    @Test
    void rejectsForeignRootElement() {
        assertThrows(PluginIoException.class, () -> read("<plugin-io><plugin/></plugin-io>"));
    }

    @Test
    void rejectsDoctypes() {
        assertThrows(PluginIoException.class,
                () -> read("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>" + DOC));
    }

    @Test
    void pluginJarDeclarationsGetTheJarsCoordinates(@TempDir Path tmp) throws Exception {
        Path jar = tmp.resolve("plugin.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry(PluginIoReader.PLUGIN_JAR_ENTRY));
            out.write(DOC.replace(" groupId=\"org.example\" artifactId=\"gen-maven-plugin\"", "")
                    .getBytes(StandardCharsets.UTF_8));
        }
        List<PluginIo> plugins = PluginIoReader.readFromPluginJar(jar, "g", "a");
        assertEquals("g:a", plugins.get(0).key());
        assertTrue(PluginIoReader.readFromPluginJar(tmp.resolve("none.jar"), "g", "a").isEmpty());
    }

    private static List<PluginIo> read(String xml) {
        return PluginIoReader.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "test");
    }
}
