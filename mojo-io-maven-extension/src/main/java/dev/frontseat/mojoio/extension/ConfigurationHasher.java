package dev.frontseat.mojoio.extension;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
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
     * run time, after other mojos (e.g. jacoco:prepare-agent) set it. The key must see the value the mojo
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
        throw new NotCacheableException("has unsupported type " + value.getClass().getName());
    }
}
