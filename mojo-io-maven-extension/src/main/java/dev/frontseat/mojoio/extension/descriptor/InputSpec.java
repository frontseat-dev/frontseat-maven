package dev.frontseat.mojoio.extension.descriptor;

import dev.frontseat.mojoio.cache.CacheKey;
import dev.frontseat.mojoio.extension.ExecutionContext;
import dev.frontseat.mojoio.extension.NotCacheableException;

/** A declared input of a mojo execution. Each contributes one or more components to the cache key. */
@FunctionalInterface
public interface InputSpec {

    void contribute(ExecutionContext ctx, CacheKey key) throws NotCacheableException;
}
