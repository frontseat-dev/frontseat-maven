package dev.frontseat.mojoio.cache;

import java.util.function.UnaryOperator;

/**
 * Transforms file content before hashing (e.g. stamp tokenization). The {@link #id()} identifies the
 * transformation, so memoized hashes are only reused for the same filter.
 */
public interface ContentFilter extends UnaryOperator<byte[]> {

    String id();
}
