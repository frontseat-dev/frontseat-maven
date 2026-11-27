package dev.frontseat.mojoio.spec;

import java.util.List;
import java.util.Set;

/**
 * Inputs and outputs of one goal.
 *
 * @param goal              the mojo goal
 * @param cacheable         {@code false} when the mojo has effects that cannot be captured
 *                          (system properties, static state, the session, files outside the project)
 * @param reason            why it is not cacheable; required when {@code cacheable} is false
 * @param inputs            declared inputs; parameters they cover are hashed by content
 * @param outputs           declared outputs
 * @param ignoredParameters parameters that cannot affect outputs (logging, process plumbing)
 * @param ignoredWrites     Ant patterns, relative to the project build directory, for files the mojo
 *                          writes that are bookkeeping rather than outputs (e.g. the compiler's
 *                          {@code maven-status/**}); build systems leave them out of observed outputs
 */
public record MojoIo(String goal, boolean cacheable, String reason,
                     List<IoDeclaration> inputs, List<IoDeclaration> outputs, Set<String> ignoredParameters,
                     Set<String> ignoredWrites) {

    public MojoIo {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        ignoredParameters = Set.copyOf(ignoredParameters);
        ignoredWrites = Set.copyOf(ignoredWrites);
        if (!cacheable && (reason == null || reason.isBlank())) {
            throw new PluginIoException("mojo '" + goal + "' is not cacheable but gives no reason");
        }
    }
}
