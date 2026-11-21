package dev.frontseat.mojoio.extension;

import dev.frontseat.mojoio.cache.AntPattern;
import dev.frontseat.mojoio.cache.FileTrees;
import dev.frontseat.mojoio.extension.descriptor.ResolvedOutput;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

/**
 * Size and modification time of every file in a project directory, to find what a mojo wrote. Skips
 * {@code .git} and {@code .mvn}, nested reactor modules (they belong to other projects, possibly
 * building in parallel) and mojo-io's own files in the build directory.
 */
final class WriteSnapshot {

    /**
     * Files Maven itself writes into the build directory while a mojo runs; they are not the mojo's.
     * Maven 4 writes {@code consumer-<n>.pom} lazily, during whichever mojo first needs it.
     */
    static final List<String> MAVEN_WRITES = List.of("consumer-*.pom");

    private final Path basedir;
    private final Map<Path, long[]> files;

    private WriteSnapshot(Path basedir, Map<Path, long[]> files) {
        this.basedir = basedir;
        this.files = files;
    }

    static WriteSnapshot take(MavenSession session, MavenProject project) {
        Path basedir = project.getBasedir().toPath().toAbsolutePath().normalize();
        Path own = Path.of(project.getBuild().getDirectory()).toAbsolutePath().normalize().resolve("mojo-io");
        List<Path> nested = new ArrayList<>();
        for (MavenProject p : session.getProjects()) {
            Path dir = p.getBasedir().toPath().toAbsolutePath().normalize();
            if (!dir.equals(basedir) && dir.startsWith(basedir)) {
                nested.add(dir);
            }
        }
        Map<Path, long[]> files = new HashMap<>();
        try {
            Files.walkFileTree(basedir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (dir.equals(own) || nested.contains(dir) || name.equals(".git") || name.equals(".mvn")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        files.put(file, new long[] {attrs.size(), attrs.lastModifiedTime().toMillis()});
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE; // e.g. a temp file deleted while walking
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new WriteSnapshot(basedir, files);
    }

    /** Files created, modified or deleted between {@code this} and {@code after}. */
    Set<Path> changedSince(WriteSnapshot after) {
        Set<Path> changed = new TreeSet<>();
        after.files.forEach((f, meta) -> {
            long[] before = files.get(f);
            if (before == null || before[0] != meta[0] || before[1] != meta[1]) {
                changed.add(f);
            }
        });
        for (Path f : files.keySet()) {
            if (!after.files.containsKey(f)) {
                changed.add(f);
            }
        }
        return changed;
    }

    /**
     * The changed files no declared output covers and no ignored-write pattern (relative to
     * {@code buildDirectory}) excuses.
     */
    static List<String> undeclared(Set<Path> changed, List<ResolvedOutput> outputs, Path buildDirectory,
                                   List<String> ignoredWrites, Path basedir) {
        List<String> result = new ArrayList<>();
        for (Path f : changed) {
            if (covered(f, outputs)) {
                continue;
            }
            if (f.startsWith(buildDirectory)) {
                List<String> ignored = new ArrayList<>(MAVEN_WRITES);
                ignored.addAll(ignoredWrites);
                if (AntPattern.selected(FileTrees.relative(buildDirectory, f), ignored, List.of())) {
                    continue;
                }
            }
            result.add(FileTrees.relative(basedir, f));
        }
        return result;
    }

    private static boolean covered(Path f, List<ResolvedOutput> outputs) {
        return outputs.stream().anyMatch(o -> o.covers(f));
    }

    /**
     * Files an observed output picked up: written (created or modified) during the run, under the
     * build directory, matching its patterns, and not bookkeeping (ignored writes, Maven's own files).
     */
    static List<Path> observedFiles(Set<Path> changed, List<ResolvedOutput> outputs, Path buildDirectory,
                                    List<String> ignoredWrites) {
        List<String> ignored = new ArrayList<>(MAVEN_WRITES);
        ignored.addAll(ignoredWrites);
        List<Path> result = new ArrayList<>();
        for (Path f : changed) {
            if (!java.nio.file.Files.isRegularFile(f) || !f.startsWith(buildDirectory)
                    || AntPattern.selected(FileTrees.relative(buildDirectory, f), ignored, List.of())) {
                continue;
            }
            if (outputs.stream().anyMatch(o -> o.observed() && o.covers(f))) {
                result.add(f);
            }
        }
        return result;
    }

    Path basedir() {
        return basedir;
    }
}
