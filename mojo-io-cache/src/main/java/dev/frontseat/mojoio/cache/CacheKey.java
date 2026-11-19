package dev.frontseat.mojoio.cache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds a cache key from named components. The key is the SHA-256 of all components in insertion
 * order, so callers must add components deterministically. The component list is kept so a miss can
 * be explained by diffing two key manifests.
 */
public final class CacheKey {

    /** Bump when the key layout or entry format changes incompatibly. */
    public static final String FORMAT = "mojo-io-key-v2";

    private final List<String[]> components = new ArrayList<>();

    public CacheKey() {
        add("format", FORMAT);
    }

    public CacheKey add(String name, String value) {
        components.add(new String[] {name, value == null ? "null" : value});
        return this;
    }

    public List<String[]> components() {
        return Collections.unmodifiableList(components);
    }

    /** Human-readable manifest: one {@code name = value} line per component. */
    public String manifest() {
        StringBuilder sb = new StringBuilder();
        for (String[] c : components) {
            sb.append(c[0]).append(" = ").append(c[1].replace("\n", "\\n")).append('\n');
        }
        return sb.toString();
    }

    public String hash() {
        StringBuilder sb = new StringBuilder();
        for (String[] c : components) {
            sb.append(c[0].length()).append(':').append(c[0])
              .append(c[1].length()).append(':').append(c[1]);
        }
        return Hashing.sha256(sb.toString());
    }
}
