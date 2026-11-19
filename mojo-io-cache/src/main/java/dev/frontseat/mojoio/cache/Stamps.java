package dev.frontseat.mojoio.cache;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stamp values: strings such as the project version that end up inside outputs (jar metadata,
 * filtered resources, file names) without changing what is built. Mojos that only <em>carry</em>
 * stamps hash them as tokens ({@code @@STAMP:name@@}); on restore the current values are written back.
 *
 * <p>Values shorter than {@link #MIN_LENGTH} are ignored: replacing e.g. {@code 1.0} would hit
 * unrelated text.
 */
public final class Stamps {

    public static final int MIN_LENGTH = 5;
    public static final Stamps NONE = new Stamps(Map.of());

    private final Map<String, String> values;

    public Stamps(Map<String, String> values) {
        Map<String, String> usable = new LinkedHashMap<>();
        values.forEach((k, v) -> {
            if (v != null && v.length() >= MIN_LENGTH) {
                usable.put(k, v);
            }
        });
        // Longest value first, so a value containing another one is replaced whole.
        Map<String, String> sorted = new LinkedHashMap<>();
        usable.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().length(), a.getValue().length()))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        this.values = Collections.unmodifiableMap(sorted);
    }

    public Map<String, String> values() {
        return values;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public static String token(String name) {
        return "@@STAMP:" + name + "@@";
    }

    public String tokenize(String s) {
        if (s == null) {
            return null;
        }
        String r = s;
        for (Map.Entry<String, String> e : values.entrySet()) {
            r = r.replace(e.getValue(), token(e.getKey()));
        }
        return r;
    }

    public String detokenize(String s) {
        if (s == null) {
            return null;
        }
        String r = s;
        for (Map.Entry<String, String> e : values.entrySet()) {
            r = r.replace(token(e.getKey()), e.getValue());
        }
        return r;
    }

    /** Content filter for hashing: stamp values in text content become tokens; binary content is untouched. */
    public byte[] tokenizeContent(byte[] content) {
        if (values.isEmpty() || !isText(content)) {
            return content;
        }
        byte[] r = content;
        for (Map.Entry<String, String> e : values.entrySet()) {
            r = Bytes.replace(r, e.getValue().getBytes(StandardCharsets.UTF_8),
                    token(e.getKey()).getBytes(StandardCharsets.UTF_8));
        }
        return r;
    }

    public boolean occursIn(byte[] content) {
        for (String v : values.values()) {
            if (Bytes.indexOf(content, v.getBytes(StandardCharsets.UTF_8), 0) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** Text = no NUL byte. Good enough to tell class files and images from properties, XML, manifests. */
    public static boolean isText(byte[] content) {
        for (byte b : content) {
            if (b == 0) {
                return false;
            }
        }
        return true;
    }
}
