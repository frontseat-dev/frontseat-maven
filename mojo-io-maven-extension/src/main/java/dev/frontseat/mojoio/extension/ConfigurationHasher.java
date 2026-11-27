package dev.frontseat.mojoio.extension;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Renders the effective (interpolated) mojo configuration as a stable string. Each parameter is
 * evaluated the way Maven would evaluate it, and absolute paths are normalized so the result does not
 * depend on where the checkout lives.
 *
 * <p>Values of types we cannot render deterministically (Maven model objects, components, ...) make the
 * execution non-cacheable, unless the descriptor lists the parameter as ignored.
 *
 * <p>An artifact named by coordinates counts by its version, which must name fixed content. One built in
 * this reactor is marked as such instead: the task graph makes the execution's task depend on that
 * project's tasks, so the build system hashes its content.
 */
final class ConfigurationHasher {

    private ConfigurationHasher() {}

    static String render(ExecutionContext ctx, Set<String> ignored) throws NotCacheableException {
        Xpp3Dom config = ctx.execution().getConfiguration();
        if (config == null) {
            return "";
        }
        TreeMap<String, String> params = new TreeMap<>();
        for (Xpp3Dom child : config.getChildren()) {
            if (ignored.contains(child.getName())) {
                continue;
            }
            params.put(child.getName(), renderNode(ctx, child));
        }
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        return sb.toString();
    }

    private static String renderNode(ExecutionContext ctx, Xpp3Dom node) throws NotCacheableException {
        StringBuilder sb = new StringBuilder();
        for (String attr : node.getAttributeNames()) {
            if (!"default-value".equals(attr)) {
                sb.append('@').append(attr).append('=').append(node.getAttribute(attr)).append(' ');
            }
        }
        if (node.getChild("groupId") != null && node.getChild("artifactId") != null) {
            // An artifact written out in the configuration (an annotation processor path, a dependency
            // of a mojo): its content is fixed only by a release version.
            return sb.append(coordinateNode(ctx, node)).toString();
        }
        if (node.getChildCount() > 0) {
            sb.append('{');
            for (Xpp3Dom child : node.getChildren()) {
                sb.append(child.getName()).append(':').append(renderNode(ctx, child)).append(';');
            }
            return sb.append('}').toString();
        }
        Object value;
        try {
            value = ctx.evaluateLeaf(node);
        } catch (NotCacheableException e) {
            throw new NotCacheableException("parameter '" + node.getName() + "': " + e.getMessage(), e);
        }
        try {
            return sb.append(renderValue(ctx, value)).toString();
        } catch (NotCacheableException e) {
            throw new NotCacheableException("parameter '" + node.getName() + "' " + e.getMessage()
                    + " (add it to the descriptor's ignored parameters if it does not affect outputs)");
        }
    }

    private static final java.util.regex.Pattern LATE = java.util.regex.Pattern.compile("@\\{([^}]+)}");

    /**
     * Surefire/failsafe "late replacement": {@code @{argLine}} is substituted with the project property at
     * run time, after other mojos (e.g. jacoco:prepare-agent) set it. The fingerprint must see the value the mojo
     * will see, not the literal placeholder.
     */
    static String lateReplace(ExecutionContext ctx, String s) {
        if (s.indexOf("@{") < 0) {
            return s;
        }
        java.util.regex.Matcher m = LATE.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = ctx.project().getProperties().getProperty(m.group(1),
                    ctx.session().getUserProperties().getProperty(m.group(1)));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        return m.appendTail(sb).toString();
    }

    static String renderValue(ExecutionContext ctx, Object value) throws NotCacheableException {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return ctx.normalizer().normalize(lateReplace(ctx, s));
        }
        if (value instanceof File f) {
            return ctx.normalizer().normalize(f.getAbsolutePath());
        }
        if (value instanceof Path p) {
            return ctx.normalizer().normalize(p.toAbsolutePath().toString());
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character
                || value instanceof Enum<?>) {
            return value.toString();
        }
        if (value instanceof Collection<?> c) {
            StringBuilder sb = new StringBuilder("[");
            for (Object o : c) {
                sb.append(renderValue(ctx, o)).append(',');
            }
            return sb.append(']').toString();
        }
        if (value instanceof Object[] a) {
            StringBuilder sb = new StringBuilder("[");
            for (Object o : a) {
                sb.append(renderValue(ctx, o)).append(',');
            }
            return sb.append(']').toString();
        }
        if (value instanceof Map<?, ?> m) {
            TreeMap<String, String> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), renderValue(ctx, e.getValue()));
            }
            return sorted.toString();
        }
        if (isMavenObject(value.getClass())) {
            // The running build itself (session, project, repositories), injected under any parameter
            // name: not configuration. What the mojo reads through it is what its declaration covers.
            // (Checked first: a project also has a group, an artifact and a version.)
            return "(maven)";
        }
        String coordinate = coordinate(ctx, value);
        if (coordinate != null) {
            return coordinate;
        }
        throw new NotCacheableException("has unsupported type " + value.getClass().getName());
    }

    /**
     * An artifact named by coordinates, such as an entry of the compiler's {@code annotationProcessorPaths}
     * (any object with {@code getGroupId()}, {@code getArtifactId()} and {@code getVersion()}). A release
     * version names fixed content; a missing version is looked up in the project's dependency
     * management, as the compiler does with {@code annotationProcessorPathsUseDepMgmt}. A SNAPSHOT or a
     * version range can change content under the same name: not cacheable.
     */
    private static String coordinate(ExecutionContext ctx, Object value) throws NotCacheableException {
        String groupId = getter(value, "getGroupId");
        String artifactId = getter(value, "getArtifactId");
        if (groupId == null || artifactId == null || !hasMethod(value, "getVersion")) {
            return null;
        }
        String version = getter(value, "getVersion");
        String type = getter(value, "getType");
        String classifier = getter(value, "getClassifier");
        if (builtInReactor(ctx, groupId, artifactId)) {
            return groupId + ":" + artifactId + ":" + (type == null ? "jar" : type) + ":"
                    + (classifier == null ? "" : classifier) + " " + BUILT_IN_REACTOR;
        }
        if (version == null || version.isBlank()) {
            version = managedVersion(ctx, groupId, artifactId);
        }
        if (version == null || version.isBlank()) {
            throw new NotCacheableException("artifact " + groupId + ":" + artifactId + " has no version");
        }
        if (version.endsWith("-SNAPSHOT") || version.startsWith("[") || version.startsWith("(")) {
            throw new NotCacheableException("artifact " + groupId + ":" + artifactId + ":" + version
                    + " can change under the same version");
        }
        StringBuilder sb = new StringBuilder(groupId).append(':').append(artifactId).append(':')
                .append(type == null ? "jar" : type).append(':').append(classifier == null ? "" : classifier)
                .append(':').append(version);
        Object exclusions = invoke(value, "getExclusions");
        if (exclusions instanceof Collection<?> c && !c.isEmpty()) {
            List<String> excluded = new ArrayList<>();
            for (Object e : c) {
                excluded.add(getter(e, "getGroupId") + ":" + getter(e, "getArtifactId"));
            }
            java.util.Collections.sort(excluded);
            sb.append(" excluding ").append(excluded);
        }
        return sb.toString();
    }

    private static String coordinateNode(ExecutionContext ctx, Xpp3Dom node) throws NotCacheableException {
        java.util.TreeMap<String, String> fields = new java.util.TreeMap<>();
        for (Xpp3Dom child : node.getChildren()) {
            fields.put(child.getName(), child.getChildCount() > 0 ? renderNode(ctx, child)
                    : String.valueOf(ctx.evaluateLeaf(child)));
        }
        String groupId = fields.get("groupId");
        String artifactId = fields.get("artifactId");
        String version = fields.get("version");
        if (builtInReactor(ctx, groupId, artifactId)) {
            fields.remove("version");
            fields.put("reactor", BUILT_IN_REACTOR);
            return fields.toString();
        }
        if (version == null || version.isBlank() || "null".equals(version)) {
            version = managedVersion(ctx, groupId, artifactId);
            if (version == null) {
                throw new NotCacheableException("artifact " + groupId + ":" + artifactId + " has no version");
            }
            fields.put("version", version + " (managed)");
        }
        if (version.endsWith("-SNAPSHOT") || version.startsWith("[") || version.startsWith("(")) {
            throw new NotCacheableException("artifact " + groupId + ":" + artifactId + ":" + version
                    + " can change under the same version");
        }
        return fields.toString();
    }

    private static final String BUILT_IN_REACTOR = "built in this reactor";

    /**
     * Whether a project of this build has these coordinates (an annotation processor, a build-tools module
     * named in configuration): its version says nothing about its content.
     */
    private static boolean builtInReactor(ExecutionContext ctx, String groupId, String artifactId) {
        if (ctx.session() == null || groupId == null || artifactId == null) {
            return false;
        }
        for (org.apache.maven.project.MavenProject p : ctx.session().getAllProjects()) {
            if (p.getGroupId().equals(groupId) && p.getArtifactId().equals(artifactId)) {
                return true;
            }
        }
        return false;
    }

    private static String managedVersion(ExecutionContext ctx, String groupId, String artifactId) {
        if (ctx.project().getDependencyManagement() == null) {
            return null;
        }
        for (org.apache.maven.model.Dependency d : ctx.project().getDependencyManagement().getDependencies()) {
            if (groupId.equals(d.getGroupId()) && artifactId.equals(d.getArtifactId())) {
                return d.getVersion();
            }
        }
        return null;
    }

    private static boolean hasMethod(Object o, String name) {
        try {
            o.getClass().getMethod(name);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static Object invoke(Object o, String name) {
        try {
            return o.getClass().getMethod(name).invoke(o);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static String getter(Object o, String name) {
        Object v = invoke(o, name);
        return v instanceof String s ? s : null;
    }

    private static final List<String> MAVEN_OBJECTS = List.of(
            "org.apache.maven.execution.MavenSession", "org.apache.maven.project.MavenProject",
            "org.apache.maven.plugin.MojoExecution", "org.apache.maven.plugin.descriptor.PluginDescriptor",
            "org.apache.maven.settings.Settings", "org.apache.maven.artifact.repository.ArtifactRepository",
            "org.eclipse.aether.RepositorySystemSession", "org.eclipse.aether.repository.RemoteRepository",
            "org.eclipse.aether.repository.LocalRepository");

    private static boolean isMavenObject(Class<?> type) {
        if (type == null) {
            return false;
        }
        if (MAVEN_OBJECTS.contains(type.getName())) {
            return true;
        }
        for (Class<?> i : type.getInterfaces()) {
            if (isMavenObject(i)) {
                return true;
            }
        }
        return isMavenObject(type.getSuperclass());
    }
}
