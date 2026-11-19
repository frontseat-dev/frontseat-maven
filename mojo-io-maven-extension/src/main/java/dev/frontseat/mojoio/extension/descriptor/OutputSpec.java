package dev.frontseat.mojoio.extension.descriptor;

import dev.frontseat.mojoio.extension.ExecutionContext;
import dev.frontseat.mojoio.extension.NotCacheableException;
import java.util.List;

/** A declared output kind. Resolves to neutral {@link ResolvedOutput}s, before or after the mojo runs. */
@FunctionalInterface
public interface OutputSpec {

    List<ResolvedOutput> resolve(ExecutionContext ctx) throws NotCacheableException;
}
