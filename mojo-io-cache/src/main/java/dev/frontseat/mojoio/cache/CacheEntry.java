package dev.frontseat.mojoio.cache;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a cache hit restores: output files (paths relative to the project base directory, possibly
 * containing stamp tokens; content stored by hash), opaque state records, and the stamp values the
 * outputs were stored with, so restore can rewrite them for the current values.
 *
 * <p>A <em>redirect</em> entry stores no outputs: it says the outputs for this (stamp-free) key embed
 * stamp values that cannot be rewritten, so the real entry lives under a key that includes them.
 *
 * <p>Format {@value #HEADER}, line-based, URL-encoded fields.
 */
public record CacheEntry(String key, String mojo, List<OutputFile> files, List<StateRecord> state,
                         Map<String, String> stamps, String redirect) {

    private static final String HEADER = "mojo-io-entry 2";

    public CacheEntry {
        files = List.copyOf(files);
        state = List.copyOf(state);
        stamps = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(stamps));
    }

    public CacheEntry(String key, String mojo, List<OutputFile> files, List<StateRecord> state) {
        this(key, mojo, files, state, Map.of(), null);
    }

    public static CacheEntry redirect(String key, String mojo, String reason) {
        return new CacheEntry(key, mojo, List.of(), List.of(), Map.of(), reason);
    }

    public boolean isRedirect() {
        return redirect != null;
    }

    /** An output file: path relative to the project base directory, content hash, size and restamp mode. */
    public record OutputFile(String path, String hash, long size, Restamping.Mode mode) {
        public OutputFile(String path, String hash, long size) {
            this(path, hash, size, Restamping.Mode.RAW);
        }
    }

    /** A generic state change, e.g. {@code (property, argLine, -javaagent:...)}. */
    public record StateRecord(String kind, String name, String value) {}

    public String encode() {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        sb.append("key ").append(key).append('\n');
        sb.append("mojo ").append(enc(mojo)).append('\n');
        if (redirect != null) {
            sb.append("redirect ").append(enc(redirect)).append('\n');
        }
        stamps.forEach((k, v) -> sb.append("stamp ").append(enc(k)).append(' ').append(enc(v)).append('\n'));
        for (OutputFile f : files) {
            sb.append("file ").append(f.hash()).append(' ').append(f.size()).append(' ').append(enc(f.path()))
              .append(' ').append(f.mode().name()).append('\n');
        }
        for (StateRecord s : state) {
            sb.append("state ").append(enc(s.kind())).append(' ').append(enc(s.name())).append(' ')
              .append(enc(s.value())).append('\n');
        }
        return sb.toString();
    }

    public static CacheEntry decode(String text) {
        String[] lines = text.split("\n");
        if (lines.length == 0 || !HEADER.equals(lines[0])) {
            throw new IllegalArgumentException("Not a " + HEADER + " cache entry");
        }
        String key = null;
        String mojo = null;
        String redirect = null;
        Map<String, String> stamps = new LinkedHashMap<>();
        List<OutputFile> files = new ArrayList<>();
        List<StateRecord> state = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split(" ");
            switch (parts[0]) {
                case "key" -> key = parts[1];
                case "mojo" -> mojo = dec(parts[1]);
                case "redirect" -> redirect = dec(parts[1]);
                case "stamp" -> stamps.put(dec(parts[1]), dec(parts[2]));
                case "file" -> files.add(new OutputFile(dec(parts[3]), parts[1], Long.parseLong(parts[2]),
                        parts.length > 4 ? Restamping.Mode.valueOf(parts[4]) : Restamping.Mode.RAW));
                case "state" -> state.add(new StateRecord(dec(parts[1]), dec(parts[2]), dec(parts[3])));
                default -> throw new IllegalArgumentException("Unknown entry line: " + line);
            }
        }
        return new CacheEntry(key, mojo, files, state, stamps, redirect);
    }

    private static String enc(String s) {
        // URL encoding keeps every field free of spaces and newlines; "%00" marks null.
        return s == null ? "%00" : s.isEmpty() ? "%" : URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String dec(String s) {
        if ("%00".equals(s)) {
            return null;
        }
        return "%".equals(s) ? "" : URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
