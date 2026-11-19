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
 * @param prerequisitePhase lifecycle phase that must have run before this goal when it is invoked
 *                          directly (e.g. {@code process-test-classes} for {@code surefire:test})
 * @param inputs            declared inputs; parameters they cover are hashed by content
 * @param outputs           declared outputs
 * @param ignoredParameters parameters that cannot affect outputs (logging, process plumbing)
 * @param stampingCarry     {@code stamping="carry"}: the mojo only copies stamp values (project version,
 *                          build numbers) into its outputs, so they are excluded from the key and
 *                          rewritten on restore; otherwise ({@code exact}) they stay in the key
 */
public record MojoIo(String goal, boolean cacheable, String reason, String prerequisitePhase,
                     List<IoDeclaration> inputs, List<IoDeclaration> outputs, Set<String> ignoredParameters,
                     boolean stampingCarry) {

    public MojoIo {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        ignoredParameters = Set.copyOf(ignoredParameters);
        if (!cacheable && (reason == null || reason.isBlank())) {
            throw new PluginIoException("mojo '" + goal + "' is not cacheable but gives no reason");
        }
    }
}
