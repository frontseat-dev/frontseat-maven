package dev.frontseat.mojoio.extension.descriptor;

import dev.frontseat.mojoio.extension.ExecutionContext;
import dev.frontseat.mojoio.extension.NotCacheableException;
import java.nio.file.Path;
import java.util.Map;

/**
 * A declared output of a mojo execution. Collected after a cache-miss execution, as a map from path
 * relative to the project base directory to the file on disk.
 */
@FunctionalInterface
public interface OutputSpec {

    void collect(ExecutionContext ctx, Map<String, Path> out) throws NotCacheableException;
}
