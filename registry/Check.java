import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Checks every parameter the built-in plugin-io declarations reference against the real plugin.xml of
 * each version listed in registry/checked-versions.txt (downloaded with mvn dependency:get). A missing
 * referenced parameter fails; a missing ignored parameter is only reported. Lines starting with "!"
 * list versions expected NOT to fit.
 *
 * <pre>java registry/Check.java</pre>   (single-file Java: JDK only, no build step)
 */
public class Check {

    static final String NS = "urn:mojo-io:plugin-io:1.0";
    static final List<String> REF_ATTRS = List.of("parameter", "excludeParameter", "resourcesParameter",
            "outputDirectoryParameter", "nameParameter");

    record Decl(Set<String> refs, Set<String> ignored) {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("user.dir"));
        Map<String, Map<String, Decl>> decls = declarations(root.resolve(
                "mojo-io-maven-extension/src/main/resources/META-INF/mojo-io/builtin-plugin-io.xml"));
        Path repo = localRepository(root);
        int errors = 0;
        Set<String> checked = new TreeSet<>();
        List<String> rows = new ArrayList<>();
        for (String line : Files.readAllLines(root.resolve("registry/checked-versions.txt"))) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            boolean expectFailure = parts[0].startsWith("!");
            String key = parts[0].replaceFirst("^!", "");
            checked.add(key);
            if (!decls.containsKey(key)) {
                System.out.println("ERROR " + key + ": listed but not declared");
                errors++;
                continue;
            }
            String[] ga = key.split(":");
            for (int i = 1; i < parts.length; i++) {
                String v = parts[i];
                Path jar = repo.resolve(ga[0].replace('.', '/')).resolve(ga[1]).resolve(v).resolve(ga[1] + "-" + v + ".jar");
                if (!Files.exists(jar)) {
                    new ProcessBuilder("mvn", "-q", "-B", "dependency:get", "-Dartifact=" + key + ":" + v, "-Dtransitive=false")
                            .directory(root.toFile()).redirectErrorStream(true)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor();
                }
                if (!Files.exists(jar)) {
                    System.out.println("ERROR " + ga[1] + " " + v + ": cannot download");
                    errors++;
                    continue;
                }
                Map<String, Set<String>> mojos = mojoParameters(jar);
                List<String> problems = new ArrayList<>();
                for (Map.Entry<String, Decl> d : decls.get(key).entrySet()) {
                    Set<String> params = mojos.get(d.getKey());
                    if (params == null) {
                        problems.add("goal " + d.getKey() + " missing");
                        continue;
                    }
                    Set<String> missing = new TreeSet<>(d.getValue().refs());
                    missing.removeAll(params);
                    if (!missing.isEmpty()) {
                        problems.add(d.getKey() + ": missing " + missing);
                    }
                    Set<String> stale = new TreeSet<>(d.getValue().ignored());
                    stale.removeAll(params);
                    if (!stale.isEmpty()) {
                        System.out.println("note  " + ga[1] + " " + v + " " + d.getKey()
                                + ": ignored parameters not present (harmless): " + stale);
                    }
                }
                if (expectFailure) {
                    if (problems.isEmpty()) {
                        System.out.println("ERROR " + ga[1] + " " + v + ": listed as incompatible but now passes");
                        errors++;
                    }
                    rows.add(String.format("%-26s %-14s incompatible (expected): %s", ga[1], v, String.join("; ", problems)));
                } else {
                    errors += problems.size();
                    rows.add(String.format("%-26s %-14s %s", ga[1], v, problems.isEmpty() ? "OK" : String.join("; ", problems)));
                }
            }
        }
        rows.forEach(System.out::println);
        Set<String> unchecked = new TreeSet<>(decls.keySet());
        unchecked.removeAll(checked);
        if (!unchecked.isEmpty()) {
            System.out.println("ERROR declared but never checked: " + unchecked);
            errors++;
        }
        System.exit(errors == 0 ? 0 : 1);
    }

    /** groupId:artifactId -> goal -> referenced and ignored parameters, for cacheable built-in declarations. */
    static Map<String, Map<String, Decl>> declarations(Path builtin) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        Element root = f.newDocumentBuilder().parse(builtin.toFile()).getDocumentElement();
        Map<String, Map<String, Decl>> result = new LinkedHashMap<>();
        for (Element plugin : children(root, "plugin")) {
            String key = plugin.getAttribute("groupId") + ":" + plugin.getAttribute("artifactId");
            for (Element mojo : children(plugin, "mojo")) {
                if ("false".equals(mojo.getAttribute("cacheable"))) {
                    continue;
                }
                Set<String> refs = new LinkedHashSet<>();
                collectRefs(mojo, refs);
                Set<String> ignored = new LinkedHashSet<>();
                for (Element ig : children(mojo, "ignoredParameters")) {
                    for (String p : ig.getTextContent().split("[,\\s]+")) {
                        if (!p.isBlank()) {
                            ignored.add(p);
                        }
                    }
                }
                ignored.removeAll(refs);
                result.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(mojo.getAttribute("goal"), new Decl(refs, ignored));
            }
        }
        return result;
    }

    static void collectRefs(Element e, Set<String> refs) {
        for (String a : REF_ATTRS) {
            if (e.hasAttribute(a)) {
                refs.add(e.getAttribute(a));
            }
        }
        if (e.hasAttribute("name") && e.getAttribute("name").contains("{")) {
            Matcher m = Pattern.compile("\\{-?([A-Za-z0-9_.]+)}").matcher(e.getAttribute("name"));
            while (m.find()) {
                refs.add(m.group(1));
            }
        }
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c) {
                collectRefs(c, refs);
            }
        }
    }

    /** goal -> parameter names, from a plugin jar's descriptor (Maven 4 descriptors are namespaced). */
    static Map<String, Set<String>> mojoParameters(Path jar) throws Exception {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile()); InputStream in = zip.getInputStream(zip.getEntry("META-INF/maven/plugin.xml"))) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            Element root = f.newDocumentBuilder().parse(in).getDocumentElement();
            for (Element mojo : descendants(root, "mojo")) {
                Set<String> params = new LinkedHashSet<>();
                for (Element ps : descendants(mojo, "parameters")) {
                    for (Element p : descendants(ps, "parameter")) {
                        for (Element n : descendants(p, "name")) {
                            if (n.getParentNode() == p) {
                                params.add(n.getTextContent().trim());
                            }
                        }
                    }
                }
                for (Element g : descendants(mojo, "goal")) {
                    if (g.getParentNode() == mojo) {
                        result.put(g.getTextContent().trim(), params);
                    }
                }
            }
        }
        return result;
    }

    static List<Element> children(Element parent, String name) {
        List<Element> r = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && NS.equals(e.getNamespaceURI()) && name.equals(e.getLocalName())) {
                r.add(e);
            }
        }
        return r;
    }

    static List<Element> descendants(Element parent, String localName) {
        List<Element> r = new ArrayList<>();
        var list = parent.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < list.getLength(); i++) {
            r.add((Element) list.item(i));
        }
        return r;
    }

    static Path localRepository(Path root) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("mvn", "-q", "-B", "help:evaluate", "-Dexpression=settings.localRepository",
                "-DforceStdout").directory(root.toFile()).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return out.isEmpty() ? Path.of(System.getProperty("user.home"), ".m2", "repository") : Path.of(out);
    }
}
