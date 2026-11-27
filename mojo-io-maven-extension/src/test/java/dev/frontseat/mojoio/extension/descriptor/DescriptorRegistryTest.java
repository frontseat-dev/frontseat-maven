package dev.frontseat.mojoio.extension.descriptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frontseat.mojoio.spec.PluginIoReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
                "org.apache.maven.plugins:maven-resources-plugin:copy-resources",
                "org.apache.maven.plugins:maven-source-plugin:test-jar-no-fork",
                "org.codehaus.mojo:build-helper-maven-plugin:parse-version",
                "org.codehaus.mojo:build-helper-maven-plugin:regex-property",
                "org.codehaus.mojo:build-helper-maven-plugin:regex-properties",
                "org.jacoco:jacoco-maven-plugin:check",
                "org.apache.maven.plugins:maven-compiler-plugin:compile",
                "org.apache.maven.plugins:maven-compiler-plugin:testCompile",
                "org.apache.maven.plugins:maven-surefire-plugin:test",
                "org.jacoco:jacoco-maven-plugin:prepare-agent",
                "org.apache.maven.plugins:maven-jar-plugin:jar",
                "org.apache.maven.plugins:maven-jar-plugin:test-jar",
                "org.apache.maven.plugins:maven-source-plugin:jar-no-fork",
                "org.apache.maven.plugins:maven-failsafe-plugin:integration-test",
                "org.apache.maven.plugins:maven-failsafe-plugin:verify",
                "org.jacoco:jacoco-maven-plugin:report",
                "org.apache.maven.plugins:maven-clean-plugin:clean",
                "org.apache.maven.plugins:maven-install-plugin:install",
                "org.apache.maven.plugins:maven-deploy-plugin:deploy"), builtin.keySet());
        builtin.values().forEach(d -> assertEquals(!Set.of("clean", "install", "deploy").contains(d.goal()),
                d.cacheable(), d.goal() + ": " + d.notCacheableReason()));
        MojoCacheDescriptor surefire = builtin.get("org.apache.maven.plugins:maven-surefire-plugin:test");
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

    /** An aggregator mojo may only be cached when its declaration covers the reactor. */
    @Test
    void reactorModelsMarksADeclarationAsReactorWide() {
        String xml = """
                <plugin-io xmlns="urn:mojo-io:plugin-io:1.0">
                  <plugin groupId="g" artifactId="a">
                    <mojo goal="aggregate"><inputs><reactorModels/></inputs></mojo>
                    <mojo goal="single"><inputs><projectModel/></inputs></mojo>
                  </plugin>
                </plugin-io>
                """;
        Map<String, MojoCacheDescriptor> index = DescriptorRegistry.index(
                PluginIoReader.read(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "test"), "test");
        assertTrue(index.get("g:a:aggregate").reactorWide());
        assertFalse(index.get("g:a:single").reactorWide());
    }

    /** The declarations shipped as examples load, and satisfy the schema. */
    @Test
    void exampleDeclarationsAreSchemaValid() throws Exception {
        Path examples = Path.of("../examples/plugin-io").toAbsolutePath().normalize();
        javax.xml.validation.Validator validator = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                .newSchema(PluginIoReader.class.getResource(PluginIoReader.SCHEMA_RESOURCE)).newValidator();
        try (java.util.stream.Stream<Path> files = Files.list(examples)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".xml")).sorted().toList()) {
                validator.validate(new StreamSource(f.toFile()));
                DescriptorRegistry.index(PluginIoReader.read(f), f.toString()); // maps every declaration
            }
        }
    }

    @Test
    void projectLoadPatternsComeFromDotMvnAndExcludesApplyToFiles(@TempDir Path root) throws Exception {
        Path dir = Files.createDirectories(root.resolve(DescriptorRegistry.PROJECT_DIRECTORY));
        Files.writeString(dir.resolve("ext.xml"), """
                <plugin-io xmlns="urn:mojo-io:plugin-io:1.0">
                  <plugin groupId="org.example" artifactId="ext-maven-plugin">
                    <projectLoad>META-INF/MANIFEST.MF, *.target</projectLoad>
                  </plugin>
                  <plugin groupId="org.example" artifactId="gen-maven-plugin">
                    <mojo goal="gen">
                      <inputs>
                        <files expression="${project.build.outputDirectory}" excludes="OSGI-INF/**"/>
                      </inputs>
                    </mojo>
                  </plugin>
                </plugin-io>
                """);
        DescriptorRegistry registry = new DescriptorRegistry();
        assertEquals(List.of("META-INF/MANIFEST.MF", "*.target"), registry.projectLoad("org.example:ext-maven-plugin", root));
        assertEquals(List.of(), registry.projectLoad("org.example:other-maven-plugin", root));
        assertTrue(registry.forProject(root).get("org.example:gen-maven-plugin:gen").cacheable());
    }
}
