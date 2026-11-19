package dev.frontseat.mojoio.extension.descriptor;

import dev.frontseat.mojoio.extension.ExecutionContext;
import dev.frontseat.mojoio.extension.NotCacheableException;
import java.util.List;

/** A declared input kind. Resolves to neutral {@link ResolvedInput}s; consumers hash or export them. */
@FunctionalInterface
public interface InputSpec {

    List<ResolvedInput> resolve(ExecutionContext ctx) throws NotCacheableException;
}
