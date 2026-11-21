package dev.frontseat.mojoio.spec;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Reads {@code plugin-io.xml} documents (namespace {@value #NAMESPACE}). JDK-only, no dependencies.
 *
 * <pre>{@code
 * <plugin-io xmlns="urn:mojo-io:plugin-io:1.0">
 *   <plugin groupId="org.apache.maven.plugins" artifactId="maven-compiler-plugin">
 *     <mojo goal="compile" prerequisitePhase="process-resources">
 *       <inputs>
 *         <files parameter="compileSourceRoots"/>
 *         <classpath parameter="compilePath" excludeParameter="outputDirectory"/>
 *       </inputs>
 *       <outputs>
 *         <directory parameter="outputDirectory" includes="**&#47;*.class"/>
 *       </outputs>
 *       <ignoredParameters>verbose,showCompilationChanges</ignoredParameters>
 *     </mojo>
 *   </plugin>
 * </plugin-io>
 * }</pre>
 */
public final class PluginIoReader {

    public static final String NAMESPACE = "urn:mojo-io:plugin-io:1.0";

    /** Where a plugin ships its own declarations inside its jar. */
    public static final String PLUGIN_JAR_ENTRY = "META-INF/maven/plugin-io.xml";

    /** Classpath location of the XML schema. */
    public static final String SCHEMA_RESOURCE = "/dev/frontseat/mojoio/spec/plugin-io-1.0.xsd";

    private PluginIoReader() {}

    public static List<PluginIo> read(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in, file.toString());
        } catch (IOException e) {
            throw new PluginIoException("Cannot read " + file, e);
        }
    }

    public static List<PluginIo> read(URL url) {
        try (InputStream in = url.openStream()) {
            return read(in, url.toString());
        } catch (IOException e) {
            throw new PluginIoException("Cannot read " + url, e);
        }
    }

    /** Declarations shipped inside a plugin jar, with the coordinates filled in; empty if none. */
    public static List<PluginIo> readFromPluginJar(Path jar, String groupId, String artifactId) {
        if (jar == null || !Files.isRegularFile(jar)) {
            return List.of();
        }
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            ZipEntry entry = jarFile.getEntry(PLUGIN_JAR_ENTRY);
            if (entry == null) {
                return List.of();
            }
            try (InputStream in = jarFile.getInputStream(entry)) {
                List<PluginIo> result = new ArrayList<>();
                for (PluginIo p : read(in, jar + "!/" + PLUGIN_JAR_ENTRY)) {
                    result.add(p.withCoordinates(groupId, artifactId));
                }
                return result;
            }
        } catch (IOException e) {
            throw new PluginIoException("Cannot read " + jar, e);
        }
    }

    public static List<PluginIo> read(InputStream in, String source) {
        Element root = parse(in, source).getDocumentElement();
        if (!"plugin-io".equals(root.getLocalName()) || !NAMESPACE.equals(root.getNamespaceURI())) {
            throw new PluginIoException(source + ": root must be <plugin-io xmlns=\"" + NAMESPACE + "\">");
        }
        List<PluginIo> plugins = new ArrayList<>();
        for (Element plugin : children(root, "plugin")) {
            List<MojoIo> mojos = new ArrayList<>();
            for (Element mojo : children(plugin, "mojo")) {
                mojos.add(readMojo(mojo, source));
            }
            plugins.add(new PluginIo(attr(plugin, "groupId"), attr(plugin, "artifactId"), mojos));
        }
        return plugins;
    }

    private static MojoIo readMojo(Element mojo, String source) {
        String goal = attr(mojo, "goal");
        if (goal == null) {
            throw new PluginIoException(source + ": <mojo> requires a goal");
        }
        String cacheable = attr(mojo, "cacheable");
        Set<String> ignored = list(mojo, "ignoredParameters");
        Set<String> ignoredWrites = list(mojo, "ignoredWrites");
        try {
            return new MojoIo(goal, cacheable == null || Boolean.parseBoolean(cacheable), attr(mojo, "reason"),
                    attr(mojo, "prerequisitePhase"), declarations(mojo, "inputs"), declarations(mojo, "outputs"),
                    ignored, ignoredWrites, "carry".equals(attr(mojo, "stamping")));
        } catch (PluginIoException e) {
            throw new PluginIoException(source + ": " + e.getMessage(), e);
        }
    }

    /** Comma- or whitespace-separated values of a child element. */
    private static Set<String> list(Element mojo, String name) {
        Set<String> values = new LinkedHashSet<>();
        for (Element e : children(mojo, name)) {
            for (String p : e.getTextContent().split("[,\\s]+")) {
                if (!p.isBlank()) {
                    values.add(p.trim());
                }
            }
        }
        return values;
    }

    private static List<IoDeclaration> declarations(Element mojo, String section) {
        List<IoDeclaration> result = new ArrayList<>();
        for (Element container : children(mojo, section)) {
            for (Element decl : children(container, null)) {
                Map<String, String> attributes = new LinkedHashMap<>();
                NamedNodeMap attrs = decl.getAttributes();
                for (int i = 0; i < attrs.getLength(); i++) {
                    Node a = attrs.item(i);
                    if (a.getNamespaceURI() == null) {
                        attributes.put(a.getLocalName() != null ? a.getLocalName() : a.getNodeName(), a.getNodeValue());
                    }
                }
                result.add(new IoDeclaration(decl.getLocalName(), attributes));
            }
        }
        return result;
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && NAMESPACE.equals(e.getNamespaceURI())
                    && (localName == null || localName.equals(e.getLocalName()))) {
                result.add(e);
            }
        }
        return result;
    }

    private static String attr(Element e, String name) {
        return e.hasAttribute(name) ? e.getAttribute(name) : null;
    }

    private static Document parse(InputStream in, String source) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override
                public void fatalError(org.xml.sax.SAXParseException e) throws SAXException {
                    throw e; // report through the exception only, not stderr
                }
            });
            return builder.parse(in, source);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new PluginIoException("Cannot parse " + source + ": " + e.getMessage(), e);
        }
    }
}
