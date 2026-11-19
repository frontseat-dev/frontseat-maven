package dev.frontseat.maven.pluginio;

import java.util.Map;
import java.util.Optional;

/**
 * One declared input or output, e.g. {@code <files parameter="compileSourceRoots"/>}. The element name
 * is the {@code kind}; its attributes are kept as given. Consumers map kinds they know and must treat
 * an unknown kind as "cannot reason about this mojo" (i.e. do not cache it), so the format can grow
 * without breaking older readers.
 */
public record IoDeclaration(String kind, Map<String, String> attributes) {

    public IoDeclaration {
        attributes = Map.copyOf(attributes);
    }

    public Optional<String> attribute(String name) {
        return Optional.ofNullable(attributes.get(name));
    }

    public String required(String name) {
        String value = attributes.get(name);
        if (value == null || value.isBlank()) {
            throw new PluginIoException("<" + kind + "> requires attribute '" + name + "'");
        }
        return value;
    }
}
