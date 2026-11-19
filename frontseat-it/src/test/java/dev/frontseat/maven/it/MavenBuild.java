package dev.frontseat.maven.it;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Forks a real {@code mvn} (the one running this build) on a copy of a sample project, with the
 * freshly built extension loaded through {@code -Dmaven.ext.class.path}, the same core-extension
 * realm {@code .mvn/extensions.xml} uses.
 */
final class MavenBuild {

    private static final Pattern FRONTSEAT_LINE =
            Pattern.compile("\\[frontseat] (HIT|MISS|SKIP) +(\\S+) \\((\\S+)\\) @ ([^\\s:]+)");

    private final Path projectDir;
    private final Path cacheDir;

    private MavenBuild(Path projectDir, Path cacheDir) {
        this.projectDir = projectDir;
        this.cacheDir = cacheDir;
    }

    /** Copies {@code src/it/projects/<name>} to {@code target/it/<testName>/<checkout>}. */
    static MavenBuild checkout(String project, String testName, String checkout, Path cacheDir) {
        Path source = Path.of(System.getProperty("frontseat.it.projects")).resolve(project);
        Path target = Path.of(System.getProperty("frontseat.it.work")).resolve(testName).resolve(checkout);
        copyTree(source, target);
        return new MavenBuild(target, cacheDir);
    }

    static Path cacheDir(String testName) {
        Path dir = Path.of(System.getProperty("frontseat.it.work")).resolve(testName).resolve("cache");
        deleteTree(dir);
        return dir;
    }

    /**
     * The Maven to fork: {@code -Dfrontseat.it.maven.home=...} (e.g. a Maven 4 install), else the
     * Maven running this build.
     */
    static Path mavenHome() {
        String override = System.getProperty("frontseat.it.maven.home");
        return Path.of(override != null && !override.isBlank() ? override : System.getProperty("maven.home"));
    }

    Path dir() {
        return projectDir;
    }

    Result run(String... goals) {
        List<String> cmd = new ArrayList<>();
        cmd.add(mavenHome().resolve("bin").resolve("mvn").toString());
        cmd.add("-B");
        cmd.add("-V"); // print the Maven version, so logs show which Maven line was exercised
        cmd.add("-Dmaven.ext.class.path=" + System.getProperty("frontseat.ext.classpath"));
        cmd.add("-Dfrontseat.cache.dir=" + cacheDir);
        String localRepo = System.getProperty("frontseat.it.localRepo");
        if (localRepo != null && !localRepo.isBlank()) {
            cmd.add("-Dmaven.repo.local=" + localRepo);
        }
        cmd.addAll(List.of(goals));
        try {
            Path log = Files.createTempFile(projectDir.getParent(), "build-", ".log");
            Process process = new ProcessBuilder(cmd)
                    .directory(projectDir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                fail("Maven timed out: " + cmd);
            }
            String output = Files.readString(log, StandardCharsets.UTF_8);
            String expected = System.getProperty("frontseat.it.expectMavenVersion");
            if (expected != null && !expected.isBlank() && !output.contains("Apache Maven " + expected)) {
                fail("Expected to fork Maven " + expected + " but got:\n"
                        + output.lines().filter(l -> l.startsWith("Apache Maven")).findFirst().orElse(output));
            }
            Result result = new Result(process.exitValue(), output);
            if (result.exitCode != 0) {
                fail("Maven failed (" + result.exitCode + "): " + String.join(" ", goals) + "\n" + output);
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    record Event(String outcome, String goal, String executionId, String module) {}

    record Result(int exitCode, String log) {

        List<Event> events() {
            List<Event> events = new ArrayList<>();
            Matcher m = FRONTSEAT_LINE.matcher(log);
            while (m.find()) {
                events.add(new Event(m.group(1), m.group(2), m.group(3), m.group(4)));
            }
            return events;
        }

        /** Outcome for {@code goal} in {@code module}, e.g. {@code HIT}; {@code null} if it never ran. */
        String outcome(String module, String goal) {
            return events().stream()
                    .filter(e -> e.module().equals(module) && e.goal().equals(goal))
                    .map(Event::outcome)
                    .reduce((a, b) -> b)
                    .orElse(null);
        }

        long count(String outcome) {
            return events().stream().filter(e -> e.outcome().equals(outcome)).count();
        }
    }

    private static void copyTree(Path source, Path target) {
        deleteTree(target);
        try (Stream<Path> files = Files.walk(source)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                Path rel = source.relativize(p);
                if (rel.toString().equals("target") || rel.toString().contains("/target")
                        || rel.toString().startsWith("target/")) {
                    continue;
                }
                Path dest = target.resolve(rel.toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
