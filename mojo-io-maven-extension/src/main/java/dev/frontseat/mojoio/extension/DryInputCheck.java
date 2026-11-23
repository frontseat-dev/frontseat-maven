package dev.frontseat.mojoio.extension;

import dev.frontseat.mojoio.extension.descriptor.InputSpec;
import dev.frontseat.mojoio.extension.descriptor.MojoCacheDescriptor;
import dev.frontseat.mojoio.extension.descriptor.ResolvedInput;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

/**
 * The safety net for the task graph's blind spot ({@code mojoio.only.inputCheck}, see docs/task-graph.md).
 *
 * <p>The graph is exported in a dry run: inputs such as {@code ${project.compileSourceRoots}} are
 * evaluated against the project as loaded, before any mojo changes it. When a build system then runs a
 * task, an earlier mojo of the task (or replayed state of an earlier task) may have added a source root;
 * the mojo reads it, but the build system neither hashed it nor shipped it to a sandbox. This compares
 * the two views of one execution's file inputs: what it resolves to now ({@code live}) and what the
 * same declaration gives against a snapshot of the project taken before replay ({@code dry}, the
 * graph's view).
 *
 * <p>Only paths are compared, not contents or patterns: a live path is covered when it is, or is under,
 * a dry path. Build directories of reactor projects (generated sources, upstream outputs: produced by
 * tasks the graph knows) and the local repository are exempt.
 */
final class DryInputCheck {

    /** A path an execution reads that the dry view does not cover. */
    record Finding(String input, Path path) {
        @Override
        public String toString() {
            return path + " (input '" + input + "')";
        }
    }

    private DryInputCheck() {}

    /** The file inputs a declaration resolves to, as the task graph resolves them (no dependency resolution). */
    static List<ResolvedInput> fileInputs(ExecutionContext ctx, MojoCacheDescriptor descriptor)
            throws NotCacheableException {
        List<ResolvedInput> files = new ArrayList<>();
        for (InputSpec spec : descriptor.inputs()) {
            for (ResolvedInput in : spec.resolve(ctx)) {
                if (in.kind() == ResolvedInput.Kind.FILES) {
                    files.add(in);
                }
            }
        }
        return files;
    }

    /** Live paths outside {@code exempt} roots that no dry path covers, in input order. */
    static List<Finding> uncovered(List<ResolvedInput> live, List<ResolvedInput> dry, Collection<Path> exempt) {
        List<Path> dryPaths = new ArrayList<>();
        for (ResolvedInput in : dry) {
            in.paths().forEach(p -> dryPaths.add(normalize(p)));
        }
        List<Path> exemptRoots = exempt.stream().map(DryInputCheck::normalize).toList();
        Set<Path> seen = new LinkedHashSet<>();
        List<Finding> findings = new ArrayList<>();
        for (ResolvedInput in : live) {
            if (in.kind() != ResolvedInput.Kind.FILES) {
                continue;
            }
            for (Path p : in.paths()) {
                Path path = normalize(p);
                if (!seen.add(path) || exemptRoots.stream().anyMatch(path::startsWith)
                        || dryPaths.stream().anyMatch(path::startsWith)) {
                    continue;
                }
                findings.add(new Finding(in.name(), path));
            }
        }
        return findings;
    }

    /**
     * Roots whose content the graph accounts for in other ways: every reactor project's build directory
     * (outputs of tasks it orders; {@code dry}'s too, in case a mojo moved it) and the local repository.
     */
    static List<Path> exemptRoots(MavenSession session, MavenProject dry) {
        List<Path> roots = new ArrayList<>();
        for (MavenProject p : session.getProjects()) {
            roots.add(Path.of(p.getBuild().getDirectory()));
        }
        roots.add(Path.of(dry.getBuild().getDirectory()));
        if (session.getRepositorySession() != null && session.getRepositorySession().getLocalRepository() != null) {
            roots.add(session.getRepositorySession().getLocalRepository().getBasedir().toPath());
        }
        return roots;
    }

    /** The message for one execution's findings. */
    static String message(String label, String declaredBy, List<Finding> findings, Path basedir) {
        StringBuilder sb = new StringBuilder();
        sb.append(label).append(" reads ").append(findings.size() == 1 ? "a path" : findings.size() + " paths")
                .append(" the task graph does not list: ");
        for (int i = 0; i < findings.size() && i < 10; i++) {
            Finding f = findings.get(i);
            sb.append(i == 0 ? "" : ", ").append(display(f.path(), basedir)).append(" (input '").append(f.input())
                    .append("')");
        }
        if (findings.size() > 10) {
            sb.append(" and ").append(findings.size() - 10).append(" more");
        }
        sb.append(". The graph is exported before any mojo runs; an earlier mojo of this build, or state replayed")
                .append(" from one, added this path afterwards, so a build system did not hash it (a change will")
                .append(" not invalidate this task) and a sandbox did not receive it. Make the path visible to the")
                .append(" graph in this mojo's plugin-io declaration (now ").append(declaredBy)
                .append("; a project declaration in .mvn/plugin-io/ overrides it): hash the project's files with")
                .append(" <projectFiles/>, or name the directory with a fixed <files expression=\"...\"/>. Or keep")
                .append(" source roots that mojos add under the build directory.");
        return sb.toString();
    }

    private static String display(Path path, Path basedir) {
        Path base = normalize(basedir);
        return path.startsWith(base) && !path.equals(base) ? base.relativize(path).toString().replace('\\', '/')
                : path.toString();
    }

    private static Path normalize(Path p) {
        return p.toAbsolutePath().normalize();
    }
}
