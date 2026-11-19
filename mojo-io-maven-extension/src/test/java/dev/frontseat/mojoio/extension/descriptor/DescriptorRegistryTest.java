package dev.frontseat.mojoio.extension.descriptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frontseat.mojoio.spec.PluginIoReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DescriptorRegistryTest {

    @Test
    void builtinDeclarationsAreSchemaValid() throws Exception {
        SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                .newSchema(PluginIoReader.class.getResource(PluginIoReader.SCHEMA_RESOURCE))
                .newValidator()
                .validate(new StreamSource(
                        DescriptorRegistry.class.getResource(DescriptorRegistry.BUILTIN_RESOURCE).toString()));
    }

    @Test
    void builtinCoversTheFirstSlice() {
        Map<String, MojoCacheDescriptor> builtin = DescriptorRegistry.index(
                PluginIoReader.read(DescriptorRegistry.class.getResource(DescriptorRegistry.BUILTIN_RESOURCE)),
                "built-in");
        assertEquals(Set.of(
                "org.apache.maven.plugins:maven-resources-plugin:resources",
                "org.apache.maven.plugins:maven-resources-plugin:testResources",
                "org.apache.maven.plugins:maven-compiler-plugin:compile",
                "org.apache.maven.plugins:maven-compiler-plugin:testCompile",
                "org.apache.maven.plugins:maven-surefire-plugin:test",
                "org.jacoco:jacoco-maven-plugin:prepare-agent",
                "org.apache.maven.plugins:maven-jar-plugin:jar"), builtin.keySet());
        builtin.values().forEach(d -> assertTrue(d.cacheable(), d.goal() + ": " + d.notCacheableReason()));
        MojoCacheDescriptor surefire = builtin.get("org.apache.maven.plugins:maven-surefire-plugin:test");
        assertEquals("process-test-classes", surefire.prerequisitePhase());
        assertTrue(surefire.ignoredParameters().contains("forkNode"));
        assertTrue(surefire.ignoredParameters().contains("project"), "Maven objects are always ignored");
    }

    @Test
    void projectDeclarationsAreReadFromDotMvn(@TempDir Path root) throws Exception {
        Path dir = Files.createDirectories(root.resolve(DescriptorRegistry.PROJECT_DIRECTORY));
        Files.writeString(dir.resolve("overrides.xml"), """
                <plugin-io xmlns="urn:mojo-io:plugin-io:1.0">
                  <plugin groupId="org.apache.maven.plugins" artifactId="maven-surefire-plugin">
                    <mojo goal="test" cacheable="false" reason="tests talk to a shared database"/>
                  </plugin>
                </plugin-io>
                """);
        MojoCacheDescriptor d = new DescriptorRegistry().forProject(root)
                .get("org.apache.maven.plugins:maven-surefire-plugin:test");
        assertFalse(d.cacheable());
        assertEquals("tests talk to a shared database", d.notCacheableReason());
        assertEquals("project .mvn/plugin-io/overrides.xml", d.source());
    }

    @Test
    void unknownKindsMakeTheMojoNonCacheable() {
        String xml = """
                <plugin-io xmlns="urn:mojo-io:plugin-io:1.0">
                  <plugin groupId="g" artifactId="a">
                    <mojo goal="x"><inputs><network host="example.com"/></inputs></mojo>
                  </plugin>
                </plugin-io>
                """;
        MojoCacheDescriptor d = DescriptorRegistry.index(PluginIoReader.read(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "test"), "test").get("g:a:x");
        assertFalse(d.cacheable());
        assertTrue(d.notCacheableReason().contains("unknown input kind <network>"));
    }
}
