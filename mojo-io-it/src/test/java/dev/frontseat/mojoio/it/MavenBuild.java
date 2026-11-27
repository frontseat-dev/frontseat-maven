package dev.frontseat.mojoio.it;

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
import java.util.stream.Stream;

/**
 * Forks a real {@code mvn} (the one running this build) on a copy of a sample project, with the
 * freshly built extension loaded through {@code -Dmaven.ext.class.path}, the same core-extension
 * realm {@code .mvn/extensions.xml} uses.
 */
final class MavenBuild {

    private final Path projectDir;

    private MavenBuild(Path projectDir) {
        this.projectDir = projectDir;
    }

    /** Copies {@code src/it/projects/<name>} to {@code target/it/<testName>/<checkout>}. */
    static MavenBuild checkout(String project, String testName, String checkout) {
        Path source = Path.of(System.getProperty("mojoio.it.projects")).resolve(project);
        Path target = Path.of(System.getProperty("mojoio.it.work")).resolve(testName).resolve(checkout);
        copyTree(source, target, false);
        return new MavenBuild(target);
    }

    /** An empty {@code target/it/<testName>/<name>}, e.g. for a build system's cache. */
    static Path emptyDir(String testName, String name) {
        Path dir = Path.of(System.getProperty("mojoio.it.work")).resolve(testName).resolve(name);
        deleteTree(dir);
        return dir;
    }

    /**
     * The Maven to fork: {@code -Dmojoio.it.maven.home=...} (e.g. a Maven 4 install), else the
     * Maven running this build.
     */
    static Path mavenHome() {
        String override = System.getProperty("mojoio.it.maven.home");
        return Path.of(override != null && !override.isBlank() ? override : System.getProperty("maven.home"));
    }

    /** {@code bin/mvnd} when the forked Maven home is a Maven Daemon distribution, else {@code bin/mvn}. */
    static Path executable() {
        Path mvnd = mavenHome().resolve("bin").resolve("mvnd");
        Path mvn = mavenHome().resolve("bin").resolve("mvn");
        if (!Files.isRegularFile(mvnd) && !Files.isRegularFile(mvn)) {
            throw new IllegalStateException("neither bin/mvnd nor bin/mvn in " + mavenHome()
                    + " (set -Dmojoio.it.maven.home to a Maven or Maven Daemon installation)");
        }
        return Files.isRegularFile(mvnd) ? mvnd : mvn;
    }

    /** Whether the ITs run through the Maven Daemon (one long-lived, parallel JVM for all builds). */
    static boolean daemon() {
        return executable().getFileName().toString().equals("mvnd");
    }

    Path dir() {
        return projectDir;
    }

    /** The same Maven, run in {@code dir} instead (a build system's sandbox). */
    MavenBuild at(Path dir) {
        return new MavenBuild(dir);
    }

    Result run(String... goals) {
        Result result = runAllowingFailure(goals);
        if (result.exitCode() != 0) {
            fail("Maven failed (" + result.exitCode() + "): " + String.join(" ", goals) + "\n" + result.log());
        }
        return result;
    }

    /** Copies every {@code target/} directory of {@code modules} from another checkout into this one. */
    void copyTargetsFrom(MavenBuild other, String... modules) {
        for (String module : modules) {
            copyTree(other.dir().resolve(module).resolve("target"), dir().resolve(module).resolve("target"), true);
        }
    }

    Result runAllowingFailure(String... goals) {
        List<String> cmd = new ArrayList<>();
        cmd.add(executable().toString());
        cmd.add("-B");
        cmd.add("-V"); // print the Maven version, so logs show which Maven line was exercised
        cmd.add("-Dmaven.ext.class.path=" + System.getProperty("mojoio.ext.classpath"));
        String localRepo = System.getProperty("mojoio.it.localRepo");
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
            String expected = System.getProperty("mojoio.it.expectMavenVersion");
            if (expected != null && !expected.isBlank() && !output.contains("Apache Maven " + expected)) {
                fail("Expected to fork Maven " + expected + " but got:\n"
                        + output.lines().filter(l -> l.startsWith("Apache Maven")).findFirst().orElse(output));
            }
            return new Result(process.exitValue(), output);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    record Result(int exitCode, String log) {}

    private static void copyTree(Path source, Path target, boolean includeTarget) {
        deleteTree(target);
        try (Stream<Path> files = Files.walk(source)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                Path rel = source.relativize(p);
                if (!includeTarget && (rel.toString().equals("target") || rel.toString().contains("/target")
                        || rel.toString().startsWith("target/"))) {
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
