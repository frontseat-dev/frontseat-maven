package dev.frontseat.mojoio.extension;

import dev.frontseat.mojoio.cache.CacheEntry;
import dev.frontseat.mojoio.spec.StateChange;
import dev.frontseat.mojoio.spec.StateFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records and replays {@link StateFile}s in {@code target/mojo-io/state/}. Because the files live with
 * the outputs, any tool that restores {@code target/} (an orchestrator's cache, a CI cache, a copied
 * directory) also restores what the mojos changed in the in-memory project.
 */
final class StateFiles {

    static final String DIRECTORY = "mojo-io/state";

    private static final Logger LOG = LoggerFactory.getLogger(StateFiles.class);

    /**
     * The state files this build recorded or replayed, per project directory. A later mojo of the same
     * build can delete them (a clean bound to an early phase) while what they describe still holds in
     * memory: they are written again, so the next build that resumes from {@code target/} replays it.
     */
    private static final java.util.Map<Path, java.util.Map<Path, StateFile>> KNOWN = new java.util.HashMap<>();

    private StateFiles() {}

    /** Starts a build: forgets the files of earlier builds in this JVM (mvnd). */
    static void reset() {
        synchronized (KNOWN) {
            KNOWN.clear();
        }
    }

    private static void remember(MavenProject project, Path file, StateFile state) {
        synchronized (KNOWN) {
            KNOWN.computeIfAbsent(directory(project), d -> new java.util.LinkedHashMap<>()).put(file, state);
        }
    }

    /** Writes back the state files of {@code project} this build knows about and something deleted. */
    static void restoreDeleted(MavenProject project) {
        java.util.Map<Path, StateFile> known;
        synchronized (KNOWN) {
            known = new java.util.LinkedHashMap<>(KNOWN.getOrDefault(directory(project), java.util.Map.of()));
        }
        known.forEach((file, state) -> {
            if (!Files.exists(file)) {
                LOG.debug("[mojo-io] rewriting deleted state file {}", file);
                state.write(file);
            }
        });
    }

    static Path directory(MavenProject project) {
        return Path.of(project.getBuild().getDirectory()).resolve(DIRECTORY);
    }

    static Path file(MavenProject project, MojoExecution m) {
        return directory(project).resolve(m.getArtifactId() + "-" + m.getGoal() + "-" + m.getExecutionId() + ".state");
    }

    /** {@code groupId:artifactId:version:goal@executionId}. */
    static String executionId(MojoExecution m) {
        return CacheKeyCalculator.mojoId(m) + "@" + m.getExecutionId();
    }

    /** The identity used to decide whether a recorded execution is part of the current plan. */
    static String identity(String executionId) {
        // g:a:v:goal@id -> g:a:goal@id (another plugin version still means "this execution will run")
        String[] at = executionId.split("@", 2);
        String[] gav = at[0].split(":");
        return gav.length == 4 ? gav[0] + ":" + gav[1] + ":" + gav[3] + "@" + at[1] : executionId;
    }

    static void record(MavenProject project, MojoExecution m, List<CacheEntry.StateRecord> records,
                       String incompleteReason, String phase) {
        List<StateChange> changes = new ArrayList<>();
        for (CacheEntry.StateRecord r : records) {
            changes.add(new StateChange(r.kind(), r.name(), r.value()));
        }
        restoreDeleted(project);
        StateFile state = new StateFile(executionId(m), phase, changes, incompleteReason);
        state.write(file(project, m));
        remember(project, file(project, m), state);
    }

    /**
     * Replays the recorded state of every execution that is not in {@code plan}: those mojos will not
     * run in this build, but later mojos may depend on what they did (e.g. JaCoCo's {@code argLine}).
     */
    /**
     * @return the replayed records per execution identity ({@code g:a:goal@id}): if one of those
     *         executions runs later in the same build anyway (Maven 4's concurrent builder calls the
     *         strategy once per lifecycle step, with partial plans), its diff misses what replay already
     *         applied, and the caller merges these back in.
     */
    static java.util.Map<String, List<CacheEntry.StateRecord>> replayPending(MavenProject project,
            Collection<MojoExecution> plan, PathNormalizer normalizer,
            org.apache.maven.project.MavenProjectHelper helper) {
        return replayPending(project, plan, normalizer, helper, state -> true);
    }

    /** As above, for the recorded executions {@code accept} lets through. */
    static java.util.Map<String, List<CacheEntry.StateRecord>> replayPending(MavenProject project,
            Collection<MojoExecution> plan, PathNormalizer normalizer,
            org.apache.maven.project.MavenProjectHelper helper, java.util.function.Predicate<StateFile> accept) {
        java.util.Map<String, List<CacheEntry.StateRecord>> applied = new java.util.HashMap<>();
        Path dir = directory(project);
        if (!Files.isDirectory(dir)) {
            return applied;
        }
        Set<String> planned = new LinkedHashSet<>();
        for (MojoExecution m : plan) {
            planned.add(identity(executionId(m)));
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".state")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Path f : files) {
            StateFile state;
            try {
                state = StateFile.read(f);
            } catch (RuntimeException e) {
                LOG.warn("[mojo-io] ignoring unreadable state file {}: {}", f, e.getMessage());
                continue;
            }
            if (planned.contains(identity(state.execution())) || !accept.test(state)) {
                continue;
            }
            if (!state.complete()) {
                LOG.warn("[mojo-io] cannot replay {} for {}: {}", state.execution(), project.getArtifactId(),
                        state.incompleteReason());
                continue;
            }
            Set<Path> missing = new LinkedHashSet<>();
            List<CacheEntry.StateRecord> records = new ArrayList<>();
            for (StateChange c : state.changes()) {
                missing.addAll(normalizer.missingRepositoryFiles(c.value()));
                records.add(new CacheEntry.StateRecord(c.kind(), c.name(), c.value()));
            }
            if (!missing.isEmpty()) {
                LOG.warn("[mojo-io] cannot replay {}: needs {}", state.execution(), missing);
                continue;
            }
            ProjectState.apply(records, project, normalizer, helper);
            remember(project, f, state);
            applied.put(identity(state.execution()), records);
            if (records.isEmpty()) {
                continue;
            }
            LOG.info("[mojo-io] STATE {} @ {} replayed {} change(s)", state.execution(), project.getArtifactId(),
                    records.size());
        }
        return applied;
    }
}
