package dev.frontseat.mojoio.spec;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Keeps the XSD and the reader in agreement. */
class SchemaTest {

    static Schema schema() throws Exception {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        return factory.newSchema(SchemaTest.class.getResource(PluginIoReader.SCHEMA_RESOURCE));
    }

    static void validate(String xml) throws Exception {
        schema().newValidator().validate(new StreamSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void acceptsAllKinds() throws Exception {
        validate("""
                <plugin-io xmlns="urn:mojo-io:plugin-io:1.0">
                  <plugin>
                    <mojo goal="a" prerequisitePhase="compile">
                      <inputs>
                        <files parameter="p"/><classpath parameter="c" excludeParameter="o"/><testClasspath/>
                        <resources parameter="r"/><projectProperty nameParameter="n" defaultName="argLine"/>
                      </inputs>
                      <outputs>
                        <directory parameter="o" includes="**/*.class"/>
                        <copiedResources resourcesParameter="r" outputDirectoryParameter="o"/>
                        <javaagentDestFiles parameter="argLine"/>
                      </outputs>
                      <ignoredParameters>x</ignoredParameters>
                    </mojo>
                    <mojo goal="b" cacheable="false" reason="r"/>
                  </plugin>
                </plugin-io>
                """);
    }

    @Test
    void rejectsUnknownKinds() {
        Assertions.assertThrows(Exception.class, () -> validate("""
                <plugin-io xmlns="urn:mojo-io:plugin-io:1.0">
                  <plugin><mojo goal="a"><inputs><network host="x"/></inputs></mojo></plugin>
                </plugin-io>
                """));
    }
}
