package dev.frontseat.mojoio.cache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Decides how a stored output can follow a change of stamp values, and rewrites it on restore.
 *
 * <ul>
 *   <li>{@link Mode#RAW}: contains no stamp value; restored byte for byte.</li>
 *   <li>{@link Mode#TEXT}: a text file containing stamp values; occurrences are rewritten.</li>
 *   <li>{@link Mode#ARCHIVE}: a jar/zip whose stamp occurrences are all in text entries
 *       ({@code pom.properties}, {@code MANIFEST.MF}, embedded poms, filtered resources); those
 *       entries are rewritten, everything else is copied, entry order and times are kept.</li>
 *   <li>{@link Mode#UNSTAMPABLE}: a stamp value sits where it cannot be rewritten safely (e.g. a
 *       version constant compiled into a class file). Such outputs pin the entry to exact stamps.</li>
 * </ul>
 */
public final class Restamping {

    public enum Mode { RAW, TEXT, ARCHIVE, UNSTAMPABLE }

    private Restamping() {}

    public static Mode classify(Path file, Stamps stamps) {
        if (stamps.isEmpty()) {
            return Mode.RAW;
        }
        try {
            if (isArchive(file)) {
                boolean any = false;
                try (ZipFile zip = new ZipFile(file.toFile())) {
                    for (ZipEntry e : Collections.list(zip.entries())) {
                        if (e.isDirectory()) {
                            continue;
                        }
                        byte[] content;
                        try (InputStream in = zip.getInputStream(e)) {
                            content = in.readAllBytes();
                        }
                        if (stamps.occursIn(content)) {
                            if (!Stamps.isText(content)) {
                                return Mode.UNSTAMPABLE;
                            }
                            any = true;
                        }
                        if (stamps.occursIn(e.getName().getBytes(StandardCharsets.UTF_8))) {
                            return Mode.UNSTAMPABLE; // renaming entries is out of scope
                        }
                    }
                }
                return any ? Mode.ARCHIVE : Mode.RAW;
            }
            byte[] content = Files.readAllBytes(file);
            if (!stamps.occursIn(content)) {
                return Mode.RAW;
            }
            return Stamps.isText(content) ? Mode.TEXT : Mode.UNSTAMPABLE;
        } catch (IOException e) {
            return Mode.UNSTAMPABLE;
        }
    }

    /**
     * Writes {@code source} (stored with stamp values {@code from}) to {@code target}, rewritten for
     * stamp values {@code to}.
     */
    public static void restamp(Path source, Path target, Mode mode, Map<String, String> from, Map<String, String> to) {
        List<byte[][]> pairs = new ArrayList<>();
        from.forEach((name, old) -> {
            String now = to.get(name);
            if (now != null && !now.equals(old)) {
                pairs.add(new byte[][] {old.getBytes(StandardCharsets.UTF_8), now.getBytes(StandardCharsets.UTF_8)});
            }
        });
        pairs.sort((a, b) -> Integer.compare(b[0].length, a[0].length));
        try {
            Files.createDirectories(target.getParent());
            if (pairs.isEmpty() || mode == Mode.RAW || mode == Mode.UNSTAMPABLE) {
                Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else if (mode == Mode.TEXT) {
                Files.write(target, apply(Files.readAllBytes(source), pairs));
            } else {
                rewriteArchive(source, target, pairs);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot restamp " + source, e);
        }
    }

    private static byte[] apply(byte[] content, List<byte[][]> pairs) {
        byte[] r = content;
        for (byte[][] p : pairs) {
            r = Bytes.replace(r, p[0], p[1]);
        }
        return r;
    }

    private static void rewriteArchive(Path source, Path target, List<byte[][]> pairs) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".restamp.tmp");
        try (ZipFile zip = new ZipFile(source.toFile());
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
            for (ZipEntry e : Collections.list(zip.entries())) {
                byte[] content;
                try (InputStream in = zip.getInputStream(e)) {
                    content = in.readAllBytes();
                }
                if (!e.isDirectory() && Stamps.isText(content)) {
                    content = apply(content, pairs);
                }
                ZipEntry copy = new ZipEntry(e.getName());
                copy.setTime(e.getTime());
                if (e.getComment() != null) {
                    copy.setComment(e.getComment());
                }
                if (e.getMethod() == ZipEntry.STORED) {
                    CRC32 crc = new CRC32();
                    crc.update(content);
                    copy.setMethod(ZipEntry.STORED);
                    copy.setSize(content.length);
                    copy.setCompressedSize(content.length);
                    copy.setCrc(crc.getValue());
                }
                out.putNextEntry(copy);
                out.write(content);
                out.closeEntry();
            }
        }
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    static boolean isArchive(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jar") || name.endsWith(".zip") || name.endsWith(".war");
    }

    @SuppressWarnings("unused")
    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        in.transferTo(out);
        return out.toByteArray();
    }
}
