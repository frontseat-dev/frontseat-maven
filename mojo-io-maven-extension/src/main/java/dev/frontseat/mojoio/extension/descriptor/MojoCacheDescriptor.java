package dev.frontseat.mojoio.extension.descriptor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Executable form of a plugin-io declaration: how one plugin goal is cached: which inputs go into the key, which files are its outputs,
 * which configuration parameters to leave out of the configuration hash, and which lifecycle phase it
 * needs to have run before it when invoked directly from the command line.
 *
 * <p>The effective configuration is always hashed, so inputs only need to cover what the configuration
 * hash cannot see: the <em>content</em> of files and directories it points at.
 */
public final class MojoCacheDescriptor {

    /** Parameters that are Maven objects rather than configuration. Never hashed. */
    public static final Set<String> ALWAYS_IGNORED = Set.of(
            "project", "session", "mojoExecution", "mojo", "plugin", "pluginDescriptor", "settings",
            "reactorProjects", "localRepository", "remoteRepositories", "repoSession",
            "repositorySystemSession", "projectArtifact", "pluginArtifactMap", "projectArtifactMap",
            "pluginArtifacts");

    private final String pluginKey;
    private final String goal;
    private final String notCacheableReason;
    private final List<InputSpec> inputs;
    private final List<OutputSpec> outputs;
    private final Set<String> ignoredParameters;
    private final String prerequisitePhase;
    private final String source;
    private final boolean stampingCarry;
    private final List<String> ignoredWrites;

    private MojoCacheDescriptor(Builder b) {
        this.ignoredWrites = List.copyOf(b.ignoredWrites);
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
        this.prerequisitePhase = b.prerequisitePhase;
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

    /** Ant patterns, relative to the build directory, of bookkeeping writes strict mode does not report. */
    public List<String> ignoredWrites() {
        return ignoredWrites;
    }

    /** Where this descriptor came from (built-in, plugin, project file); shown in key manifests. */
    public String source() {
        return source;
    }

    /** Lifecycle phase to run first when this goal is invoked directly, or {@code null}. */
    public String prerequisitePhase() {
        return prerequisitePhase;
    }

    public static final class Builder {
        private final String pluginKey;
        private final String goal;
        private String notCacheableReason;
        private final List<InputSpec> inputs = new ArrayList<>();
        private final List<OutputSpec> outputs = new ArrayList<>();
        private final Set<String> ignored = new LinkedHashSet<>();
        private String prerequisitePhase;
        private String source = "code";
        private boolean stampingCarry;
        private final List<String> ignoredWrites = new ArrayList<>();

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

        public Builder stampingCarry(boolean carry) {
            this.stampingCarry = carry;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Builder prerequisitePhase(String phase) {
            this.prerequisitePhase = phase;
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
