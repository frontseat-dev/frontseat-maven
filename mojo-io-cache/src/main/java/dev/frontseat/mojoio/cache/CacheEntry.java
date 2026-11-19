package dev.frontseat.mojoio.cache;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What a cache hit restores: output files (relative to the project base directory, content stored
 * by hash) plus opaque state records interpreted by the caller (e.g. a MavenProject state diff).
 *
 * <p>Serialised as a small line-based text file so entries are easy to inspect by hand.
 */
public record CacheEntry(String key, String mojo, List<OutputFile> files, List<StateRecord> state) {

    private static final String HEADER = "mojo-io-entry 1";

    public CacheEntry {
        files = List.copyOf(files);
        state = List.copyOf(state);
    }

    /** An output file: path relative to the project base directory, and the hash of its content. */
    public record OutputFile(String path, String hash, long size) {}

    /** A generic state change, e.g. {@code (property, argLine, -javaagent:...)}. */
    public record StateRecord(String kind, String name, String value) {}

    public String encode() {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        sb.append("key ").append(key).append('\n');
        sb.append("mojo ").append(enc(mojo)).append('\n');
        for (OutputFile f : files) {
            sb.append("file ").append(f.hash()).append(' ').append(f.size()).append(' ').append(enc(f.path())).append('\n');
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
            throw new IllegalArgumentException("Not a frontseat cache entry");
        }
        String key = null;
        String mojo = null;
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
                case "file" -> files.add(new OutputFile(dec(parts[3]), parts[1], Long.parseLong(parts[2])));
                case "state" -> state.add(new StateRecord(dec(parts[1]), dec(parts[2]), dec(parts[3])));
                default -> throw new IllegalArgumentException("Unknown entry line: " + line);
            }
        }
        return new CacheEntry(key, mojo, files, state);
    }

    public void write(Path file) {
        try {
            Files.writeString(file, encode(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
