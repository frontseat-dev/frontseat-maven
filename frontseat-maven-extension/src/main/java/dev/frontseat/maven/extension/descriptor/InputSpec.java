package dev.frontseat.maven.extension.descriptor;

import dev.frontseat.maven.cache.CacheKey;
import dev.frontseat.maven.extension.ExecutionContext;
import dev.frontseat.maven.extension.NotCacheableException;

/** A declared input of a mojo execution. Each contributes one or more components to the cache key. */
@FunctionalInterface
public interface InputSpec {

    void contribute(ExecutionContext ctx, CacheKey key) throws NotCacheableException;
}
