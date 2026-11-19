package dev.frontseat.mojoio.spec;

import java.util.Collection;
import java.util.Map;

/** Minimal deterministic JSON writer (maps keep insertion order), so the spec module stays JDK-only. */
public final class Json {

    private Json() {}

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, 0);
        return sb.append('\n').toString();
    }

    private static void write(Object v, StringBuilder sb, int indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            string(s, sb);
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                pad(sb, indent + 1);
                string(String.valueOf(e.getKey()), sb);
                sb.append(": ");
                write(e.getValue(), sb, indent + 1);
                sb.append(++i < map.size() ? ",\n" : "\n");
            }
            pad(sb, indent);
            sb.append('}');
        } else if (v instanceof Collection<?> list) {
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            boolean simple = list.stream().allMatch(o -> !(o instanceof Map) && !(o instanceof Collection));
            if (simple) {
                sb.append('[');
                int i = 0;
                for (Object o : list) {
                    write(o, sb, indent);
                    if (++i < list.size()) {
                        sb.append(", ");
                    }
                }
                sb.append(']');
                return;
            }
            sb.append("[\n");
            int i = 0;
            for (Object o : list) {
                pad(sb, indent + 1);
                write(o, sb, indent + 1);
                sb.append(++i < list.size() ? ",\n" : "\n");
            }
            pad(sb, indent);
            sb.append(']');
        } else {
            throw new IllegalArgumentException("Cannot write " + v.getClass());
        }
    }

    private static void pad(StringBuilder sb, int indent) {
        sb.append("  ".repeat(indent));
    }

    private static void string(String s, StringBuilder sb) {
        sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
