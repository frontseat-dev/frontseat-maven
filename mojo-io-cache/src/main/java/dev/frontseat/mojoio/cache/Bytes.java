package dev.frontseat.mojoio.cache;

import java.io.ByteArrayOutputStream;

/** Byte-array search and replace. */
final class Bytes {

    private Bytes() {}

    static int indexOf(byte[] data, byte[] pattern, int from) {
        if (pattern.length == 0) {
            return -1;
        }
        outer:
        for (int i = from; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    static byte[] replace(byte[] data, byte[] from, byte[] to) {
        int i = indexOf(data, from, 0);
        if (i < 0) {
            return data;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        int start = 0;
        while (i >= 0) {
            out.write(data, start, i - start);
            out.write(to, 0, to.length);
            start = i + from.length;
            i = indexOf(data, from, start);
        }
        out.write(data, start, data.length - start);
        return out.toByteArray();
    }
}
