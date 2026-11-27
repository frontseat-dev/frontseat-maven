package dev.frontseat.mojoio.extension.descriptor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Executable form of a plugin-io declaration: what one plugin goal reads (its inputs), which files are
 * its outputs, and which configuration parameters to leave out of its fingerprint.
 *
 * <p>The effective configuration is always part of the fingerprint, so inputs only need to cover what it
 * cannot see: the <em>content</em> of files and directories it points at.
 */
public final class MojoCacheDescriptor {

    /** Parameters that are Maven objects rather than configuration. Never hashed. */
    public static final Set<String> ALWAYS_IGNORED = Set.of(
            "project", "session", "mojoExecution", "mojo", "plugin", "pluginDescriptor", "settings",
            "reactorProjects", "localRepository", "remoteRepositories", "remoteProjectRepositories",
            "remotePluginRepositories", "repoSession",
            "repositorySystemSession", "projectArtifact", "pluginArtifactMap", "projectArtifactMap",
            "pluginArtifacts");

    private final String pluginKey;
    private final String goal;
    private final String notCacheableReason;
    private final List<InputSpec> inputs;
    private final List<OutputSpec> outputs;
    private final Set<String> ignoredParameters;
    private final String source;
    private final boolean stampingCarry;
    private final List<String> ignoredWrites;
    private final boolean reactorWide;

    private MojoCacheDescriptor(Builder b) {
        this.ignoredWrites = List.copyOf(b.ignoredWrites);
        this.reactorWide = b.reactorWide;
        this.source = b.source;
        this.stampingCarry = b.stampingCarry;
        this.pluginKey = b.pluginKey;
        this.goal = b.goal;
        this.notCacheableReason = b.notCacheableReason;
        this.inputs = List.copyOf(b.inputs);
        this.outputs = List.copyOf(b.outputs);
        Set<String> ignored = new LinkedHashSet<>(ALWAYS_IGNORED);
        ignored.addAll(b.ignored);
        this.ignoredParameters = Set.copyOf(ignored);
    }

    public static Builder forGoal(String groupId, String artifactId, String goal) {
        return new Builder(groupId + ":" + artifactId, goal);
    }

    public String pluginKey() {
        return pluginKey;
    }

    public String goal() {
        return goal;
    }

    public boolean cacheable() {
        return notCacheableReason == null;
    }

    public String notCacheableReason() {
        return notCacheableReason;
    }

    public List<InputSpec> inputs() {
        return inputs;
    }

    public List<OutputSpec> outputs() {
        return outputs;
    }

    public Set<String> ignoredParameters() {
        return ignoredParameters;
    }

    /**
     * {@code stamping="carry"}: stamp values only travel into outputs, so they are tokenized in the key
     * and rewritten on restore. Otherwise they are part of the key.
     */
    public boolean stampingCarry() {
        return stampingCarry;
    }

    /** Ant patterns, relative to the build directory, of bookkeeping writes that are no outputs. */
    public List<String> ignoredWrites() {
        return ignoredWrites;
    }

    /**
     * The declaration covers the whole reactor (it has a {@code reactorModels} input): the only case in
     * which an aggregator mojo is cached.
     */
    public boolean reactorWide() {
        return reactorWide;
    }

    /** Where this descriptor came from (built-in, plugin, project file); the graph's declaredBy. */
    public String source() {
        return source;
    }

    private Builder copy() {
        Builder b = new Builder(pluginKey, goal);
        b.notCacheableReason = notCacheableReason;
        b.inputs.addAll(inputs);
        b.outputs.addAll(outputs);
        b.ignored.addAll(ignoredParameters);
        b.source = source;
        b.stampingCarry = stampingCarry;
        b.ignoredWrites.addAll(ignoredWrites);
        b.reactorWide = reactorWide;
        return b;
    }

    /** This descriptor with more inputs, outputs and ignored writes (those of goals the mojo forks). */
    public MojoCacheDescriptor plus(List<InputSpec> moreInputs, List<OutputSpec> moreOutputs,
                                    java.util.Collection<String> moreIgnoredWrites) {
        Builder b = copy();
        b.inputs.addAll(moreInputs);
        b.outputs.addAll(moreOutputs);
        b.ignoredWrites.addAll(moreIgnoredWrites);
        return b.build();
    }

    /** This descriptor, marked not cacheable. */
    public MojoCacheDescriptor notCacheable(String reason) {
        return copy().notCacheable(reason).build();
    }

    public static final class Builder {
        private final String pluginKey;
        private final String goal;
        private String notCacheableReason;
        private final List<InputSpec> inputs = new ArrayList<>();
        private final List<OutputSpec> outputs = new ArrayList<>();
        private final Set<String> ignored = new LinkedHashSet<>();
        private String source = "code";
        private boolean stampingCarry;
        private final List<String> ignoredWrites = new ArrayList<>();
        private boolean reactorWide;

        private Builder(String pluginKey, String goal) {
            this.pluginKey = pluginKey;
            this.goal = goal;
        }

        public Builder input(InputSpec input) {
            inputs.add(input);
            return this;
        }

        public Builder output(OutputSpec output) {
            outputs.add(output);
            return this;
        }

        /**
         * Leaves parameters out of the configuration hash: either because an input already covers them
         * by content, or because they cannot affect outputs.
         */
        public Builder ignore(String... parameters) {
            ignored.addAll(List.of(parameters));
            return this;
        }

        public Builder ignoreWrites(java.util.Collection<String> patterns) {
            ignoredWrites.addAll(patterns);
            return this;
        }

        public Builder reactorWide(boolean reactorWide) {
            this.reactorWide = reactorWide;
            return this;
        }

        public Builder stampingCarry(boolean carry) {
            this.stampingCarry = carry;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Builder notCacheable(String reason) {
            this.notCacheableReason = reason;
            return this;
        }

        public MojoCacheDescriptor build() {
            return new MojoCacheDescriptor(this);
        }
    }
}
