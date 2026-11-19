package dev.frontseat.maven.extension;

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

    static String renderValue(ExecutionContext ctx, Object value) throws NotCacheableException {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return ctx.normalizer().normalize(s);
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
