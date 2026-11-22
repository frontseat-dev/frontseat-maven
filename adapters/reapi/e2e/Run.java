import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * End-to-end proof for the REAPI adapter against a running Remote Execution service (Buildbarn):
 * correctness against raw Maven, and speed.
 *
 * <pre>
 *   mise run install                                  # mojo-io into ~/.m2
 *   mvn -q -f adapters/reapi/pom.xml package          # the adapter
 *   adapters/reapi/e2e/buildbarn/start.sh             # a local Buildbarn (see its README)
 *   java adapters/reapi/e2e/Run.java                  # from the repository root
 * </pre>
 *
 * Env: REAPI_EXECUTOR (default localhost:8980), MOJOIO_MVND (path of mvnd on the worker, to also measure
 * actions through the Maven daemon), E2E_SKIP_PERF=1 to skip the 20-module benchmark.
 */
public class Run {

    static final Path REPO = Path.of("").toAbsolutePath();
    static final String EXECUTOR = System.getenv().getOrDefault("REAPI_EXECUTOR", "localhost:8980");
    static final String MVND = System.getenv("MOJOIO_MVND");
    /** New action keys for every run of this script, so "cold" is cold even on a long-lived service. */
    static final String SALT = "-Dmojoio.e2e.run=" + Long.toHexString(System.nanoTime());
    static final List<String> report = new ArrayList<>();
    static int failures;

    record Sh(String out, double seconds) {}

    record Build(double seconds, int tasks, List<String> executed) {}

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("mojoio-reapi-e2e-");
        System.out.println("work dir: " + work + "\nexecutor: " + EXECUTOR + "\n");

        // ------------------------------------------------------------ correctness (sample project)
        Path sample = REPO.resolve("mojo-io-it/src/it/projects/basic-multimodule");
        List<String> modules = List.of("lib-core", "app");
        Path raw = copy(sample, work.resolve("raw"), false);
        sh(raw, "mvn", "-B", "-q", "-o", "verify");
        String reference = fingerprint(raw, modules);

        Path a = copy(sample, work.resolve("a"), true);
        Build cold = reapi(a, "stage", "mvn");
        check(cold.executed().size() == cold.tasks(), "cold: all %d tasks executed on the remote workers (%.1fs)"
                .formatted(cold.tasks(), cold.seconds()));
        check(fingerprint(a, modules).equals(reference),
                "cold: downloaded classes, jar contents and test results identical to raw `mvn verify`");

        Path b = copy(sample, work.resolve("elsewhere/deeper/b"), true);
        Build warm = reapi(b, "stage", "mvn");
        check(warm.executed().isEmpty(), "fresh checkout at another path: all %d tasks from the action cache, none executed (%.1fs)"
                .formatted(warm.tasks(), warm.seconds()));
        check(fingerprint(b, modules).equals(reference), "fresh checkout: downloaded outputs identical to raw `mvn verify`");

        Path test = b.resolve("lib-core/src/test/java/sample/core/GreeterTest.java");
        Files.writeString(test, Files.readString(test).replace("class GreeterTest {",
                "class GreeterTest {\n\n    @Test\n    void greetsTheWorld() {\n        assertEquals(\"Hello, world!\", new Greeter().greet(\"world\"));\n    }\n"));
        Build changed = reapi(b, "stage", "mvn");
        List<String> ran = changed.executed().stream().map(t -> t.substring(t.indexOf(':') + 1)).toList();
        check(ran.contains("lib-core:test") && !ran.contains("lib-core:compile") && !ran.contains("app:compile")
                && !ran.contains("app:test"), "lib-core test changed: executed " + ran + "; lib-core:compile, app:compile and app:test from the cache");
        Path raw2 = copy(b, work.resolve("raw2"), false);
        sh(raw2, "mvn", "-B", "-q", "-o", "verify");
        check(fingerprint(b, modules).equals(fingerprint(raw2, modules)),
                "after the change: outputs identical to raw `mvn verify` of the changed sources");

        // ------------------------------------------------------------ speed (20-module reactor)
        if (System.getenv("E2E_SKIP_PERF") == null) {
            Path gen = work.resolve("bench-src");
            sh(REPO, "java", REPO.resolve("perf/Bench.java").toString(), "--generate", gen.toString());
            List<String> benchModules;
            try (Stream<Path> s = Files.list(gen)) {
                benchModules = s.map(p -> p.getFileName().toString()).filter(n -> n.matches("m\\d+")).sorted().toList();
            }
            Path rawBench = copy(gen, work.resolve("bench-raw"), false);
            sh(rawBench, "mvn", "-B", "-q", "-o", "verify");
            String benchReference = fingerprint(rawBench, benchModules);
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[] {"raw `mvn clean verify`, on this machine", secs(sh(rawBench, "mvn", "-B", "-q", "-o", "clean", "verify").seconds()), ""});

            List<String> mavens = new ArrayList<>(List.of("mvn"));
            if (MVND != null) {
                mavens.add(MVND);
            }
            for (String maven : mavens) {
                String name = maven.equals("mvn") ? "mvn" : "mvnd";
                for (String granularity : List.of("stage", "module")) {
                    Path ws = copy(gen, work.resolve("bench-" + name + "-" + granularity), true);
                    Build r = reapi(ws, granularity, maven);
                    rows.add(new String[] {"REAPI cold, %s on the worker, one action per %s".formatted(name, granularity),
                            secs(r.seconds()), r.executed().size() + " / " + r.tasks()});
                    check(fingerprint(ws, benchModules).equals(benchReference), "20 modules, %s, per %s: outputs identical to raw Maven (%d actions, %.1fs)"
                            .formatted(name, granularity, r.executed().size(), r.seconds()));
                }
            }
            Path fresh = copy(gen, work.resolve("elsewhere/bench-fresh"), true);
            Build w = reapi(fresh, "stage", "mvn");
            rows.add(new String[] {"REAPI, fresh checkout (all from the action cache)", secs(w.seconds()), w.executed().size() + " / " + w.tasks()});
            check(w.executed().isEmpty(), "20 modules, fresh checkout: all %d tasks from the action cache".formatted(w.tasks()));
            check(fingerprint(fresh, benchModules).equals(benchReference), "20 modules, fresh checkout: outputs identical to raw Maven");
            Path src = fresh.resolve("m00/src/main/java/bench/m00/C5.java");
            Files.writeString(src, Files.readString(src) + "\nclass Extra { int x = 1; }\n");
            Build c = reapi(fresh, "stage", "mvn");
            rows.add(new String[] {"REAPI after a real change in m00 (new class)", secs(c.seconds()), c.executed().size() + " / " + c.tasks()});
            Path rawChanged = copy(fresh, work.resolve("bench-raw-changed"), false);
            sh(rawChanged, "mvn", "-B", "-q", "-o", "verify");
            check(fingerprint(fresh, benchModules).equals(fingerprint(rawChanged, benchModules)),
                    "20 modules, after the change: outputs identical to raw Maven (%d of %d actions executed)".formatted(c.executed().size(), c.tasks()));

            report.add("");
            report.add("| Scenario | Seconds | Actions executed / tasks |");
            report.add("|---|---|---|");
            rows.forEach(r -> report.add("| " + r[0] + " | " + r[1] + " | " + r[2] + " |"));
        }

        Files.writeString(work.resolve("report.md"), String.join("\n", report) + "\n");
        System.out.println("\n" + String.join("\n", report) + "\n\nreport: " + work.resolve("report.md"));
        System.exit(failures == 0 ? 0 : 1);
    }

    static String secs(double s) {
        return "%.1f".formatted(s);
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        report.add("- " + (ok ? "✅ " : "❌ ") + what);
        if (!ok) {
            failures++;
        }
    }

    /** Builds the workspace on the remote service and downloads the outputs. */
    static Build reapi(Path workspace, String granularity, String workerMaven) throws Exception {
        Path adapter = REPO.resolve("adapters/reapi/target");
        List<String> cmd = new ArrayList<>(List.of("java", "-cp", adapter.resolve("classes") + ":" + adapter.resolve("lib") + "/*",
                "dev.frontseat.mojoio.reapi.ReapiBuild", "--workspace=" + workspace, "--executor=" + EXECUTOR,
                "--granularity=" + granularity, "--maven=" + workerMaven, "--download", "--maven-arg=-o", "--maven-arg=" + SALT,
                "--env=PATH=" + System.getenv("PATH"), "--env=HOME=" + System.getProperty("user.home")));
        if (!workerMaven.equals("mvn")) {
            // Parallel actions start extra daemons; keep them warm between actions.
            cmd.add("--maven-arg=-Dmvnd.duplicateDaemonGracePeriod=3h");
        }
        for (String name : List.of("JAVA_HOME", "_JAVA_OPTIONS")) {
            if (System.getenv(name) != null) {
                cmd.add("--env=" + name + "=" + System.getenv(name));
            }
        }
        Sh r = sh(workspace, cmd.toArray(String[]::new));
        List<String> executed = new ArrayList<>();
        int tasks = 0;
        Matcher m = Pattern.compile("^(EXEC|HIT)\\s+(\\S+) ", Pattern.MULTILINE).matcher(r.out());
        while (m.find()) {
            tasks++;
            if (m.group(1).equals("EXEC")) {
                executed.add(m.group(2));
            }
        }
        return new Build(r.seconds(), tasks, executed);
    }

    static Sh sh(Path cwd, String... command) throws Exception {
        long start = System.nanoTime();
        Process p = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            System.err.println(out);
            throw new IllegalStateException("failed: " + String.join(" ", command) + " in " + cwd);
        }
        return new Sh(out, (System.nanoTime() - start) / 1e9);
    }

    /** Copies sources (no build output); {@code extension} adds .mvn/extensions.xml for mojo-io. */
    static Path copy(Path source, Path target, boolean extension) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path rel = source.relativize(p);
                boolean skip = false;
                for (Path part : rel) {
                    skip |= List.of("target", ".mvn", ".git").contains(part.toString());
                }
                if (skip) {
                    continue;
                }
                Path to = target.resolve(rel.toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(to);
                } else {
                    Files.createDirectories(to.getParent());
                    Files.copy(p, to);
                }
            }
        }
        if (extension) {
            Files.createDirectories(target.resolve(".mvn"));
            Files.writeString(target.resolve(".mvn/extensions.xml"), """
                    <extensions><extension>
                      <groupId>dev.frontseat.mojoio</groupId><artifactId>mojo-io-maven-extension</artifactId><version>0.1.0-SNAPSHOT</version>
                    </extension></extensions>
                    """);
        }
        return target;
    }

    /** What must be identical to raw Maven: classes, test classes, jar contents, test results. */
    static String fingerprint(Path root, List<String> modules) throws IOException {
        Map<String, String> r = new TreeMap<>();
        for (String m : modules) {
            Path t = root.resolve(m).resolve("target");
            for (String dir : List.of("classes", "test-classes")) {
                if (Files.isDirectory(t.resolve(dir))) {
                    try (Stream<Path> walk = Files.walk(t.resolve(dir))) {
                        walk.filter(Files::isRegularFile).forEach(f -> r.put(m + "/" + dir + "/" + t.resolve(dir).relativize(f), sha(read(f))));
                    }
                }
            }
            if (!Files.isDirectory(t)) {
                continue;
            }
            try (Stream<Path> list = Files.list(t)) {
                for (Path jar : (Iterable<Path>) list.filter(f -> f.toString().endsWith(".jar")).sorted()::iterator) {
                    try (ZipFile zip = new ZipFile(jar.toFile())) {
                        for (ZipEntry e : zip.stream().filter(x -> !x.isDirectory()).map(x -> (ZipEntry) x).toList()) {
                            try (InputStream in = zip.getInputStream(e)) {
                                r.put(m + "/" + jar.getFileName() + "!" + e.getName(), sha(in.readAllBytes()));
                            }
                        }
                    }
                }
            }
            Path reports = t.resolve("surefire-reports");
            if (Files.isDirectory(reports)) {
                try (Stream<Path> list = Files.list(reports)) {
                    for (Path x : (Iterable<Path>) list.filter(f -> f.toString().endsWith(".xml")).sorted()::iterator) {
                        Matcher c = Pattern.compile("tests=\"(\\d+)\" errors=\"(\\d+)\" skipped=\"(\\d+)\" failures=\"(\\d+)\"")
                                .matcher(Files.readString(x));
                        r.put(m + "/tests/" + x.getFileName(), c.find() ? c.group() : "?");
                    }
                }
            }
        }
        return r.toString();
    }

    static byte[] read(Path f) {
        try {
            return Files.readAllBytes(f);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
