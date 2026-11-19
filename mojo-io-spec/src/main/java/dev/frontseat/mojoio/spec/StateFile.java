package dev.frontseat.mojoio.spec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Project-state file (format {@value #HEADER}): what one mojo execution changed in the in-memory
 * project. Written next to the outputs, in {@code target/mojo-io/state/}, so any tool that restores
 * {@code target/} restores the state too, and the extension replays it.
 *
 * <pre>
 * mojo-io-state 1
 * execution org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile@default-compile
 * change compileSourceRoot % @@BASEDIR@@/target/generated-sources/annotations
 * incomplete execution attached artifacts        (only when not replayable)
 * </pre>
 *
 * Fields are URL-encoded; {@code %} is the empty string and {@code %00} is null. An execution whose
 * changes cannot all be expressed is written with an {@code incomplete} reason; consumers must not
 * replay it and should treat the execution as not restorable.
 */
public record StateFile(String execution, List<StateChange> changes, String incompleteReason) {

    public static final String HEADER = "mojo-io-state 1";

    public StateFile {
        changes = List.copyOf(changes);
    }

    public boolean complete() {
        return incompleteReason == null;
    }

    public String encode() {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        sb.append("execution ").append(enc(execution)).append('\n');
        for (StateChange c : changes) {
            sb.append("change ").append(enc(c.kind())).append(' ').append(enc(c.name())).append(' ')
              .append(enc(c.value())).append('\n');
        }
        if (incompleteReason != null) {
            sb.append("incomplete ").append(enc(incompleteReason)).append('\n');
        }
        return sb.toString();
    }

    public static StateFile decode(String text) {
        String[] lines = text.split("\n");
        if (lines.length == 0 || !HEADER.equals(lines[0])) {
            throw new IllegalArgumentException("Not a " + HEADER + " file");
        }
        String execution = null;
        String incomplete = null;
        List<StateChange> changes = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            String[] p = lines[i].split(" ");
            switch (p[0]) {
                case "execution" -> execution = dec(p[1]);
                case "change" -> changes.add(new StateChange(dec(p[1]), dec(p[2]), dec(p[3])));
                case "incomplete" -> incomplete = dec(p[1]);
                default -> throw new IllegalArgumentException("Unknown state line: " + lines[i]);
            }
        }
        return new StateFile(execution, changes, incomplete);
    }

    public void write(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, encode(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static StateFile read(Path file) {
        try {
            return decode(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String enc(String s) {
        return s == null ? "%00" : s.isEmpty() ? "%" : URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String dec(String s) {
        return "%00".equals(s) ? null : "%".equals(s) ? "" : URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
