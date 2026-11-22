import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Benchmarks raw Maven against mojo-io on a generated reactor, with mvn and (if found) mvnd.
 *
 * <pre>
 * java perf/Bench.java [--reps N] [--modules N] [--mvnd PATH] [--out FILE]
 * </pre>
 *
 * Uses the jars in mojo-io-*&#47;target (run {@code mise run build} first), copied to a temp dir so a
 * rebuild during the run can't change what is measured. Prints a Markdown table of median wall-clock
 * times. Single-file Java on purpose: JDK only, no build step, same toolchain as the project.
 */
public class Bench {

    record Row(String tool, String mode, String scenario, double median, List<Double> runs) {}

    public static void main(String[] args) throws Exception {
        int reps = 3;
        int modules = 20;
        String mvnd = System.getenv("MOJOIO_MVND");
        String out = null;
        if (args.length == 2 && args[0].equals("--generate")) { // used by adapters/nx/e2e
            generate(Path.of(args[1]), modules, 40);
            return;
        }
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--reps" -> reps = Integer.parseInt(args[++i]);
                case "--modules" -> modules = Integer.parseInt(args[++i]);
                case "--mvnd" -> mvnd = args[++i];
                case "--out" -> out = args[++i];
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
        Path work = Files.createTempDirectory("mojoio-bench-");
        Path proj = work.resolve("proj");
        generate(proj, modules, 40);
        Path cache = work.resolve("cache");
        List<String> ext = List.of("-Dmaven.ext.class.path=" + extensionClasspath(work), "-Dmojoio.cache.dir=" + cache);
        String last = String.format("bench:m%02d", modules - 1);
        List<String> task = List.of("-pl", last, "-am", "-Dmojoio.only=" + last, "-Dmojoio.cache.enabled=false",
                "-Dmojoio.prerequisites=false", "org.apache.maven.plugins:maven-surefire-plugin:3.5.2:test@default-test");
        Path source = proj.resolve("m00/src/main/java/bench/m00/C5.java");

        List<String[]> tools = new ArrayList<>();
        tools.add(new String[] {"mvn", "mvn"});
        if (mvnd != null && Files.isExecutable(Path.of(mvnd))) {
            tools.add(new String[] {"mvnd", mvnd});
        }
        List<Row> rows = new ArrayList<>();
        for (String[] t : tools) {
            String name = t[0];
            String tool = t[1];
            for (String mode : List.of("raw", "mojo-io")) {
                stop(name, tool);
                List<String> x = mode.equals("raw") ? List.of() : ext;
                run(tool, concat(List.of("verify"), x), proj, work.resolve("warmup.log")); // downloads, daemon
                run(tool, concat(List.of("verify"), x), proj, work.resolve("warmup.log"));
                List<Object[]> cases = new ArrayList<>();
                Runnable nothing = () -> { };
                Runnable clearCache = () -> delete(cache);
                Runnable touch = () -> append(source, "// " + System.nanoTime() + "\n");
                if (mode.equals("raw")) {
                    cases.add(new Object[] {"clean verify", List.of("clean", "verify"), nothing});
                    cases.add(new Object[] {"verify, nothing changed", List.of("verify"), nothing});
                    cases.add(new Object[] {"verify, 1 source changed in m00", List.of("verify"), touch});
                    cases.add(new Object[] {"invocation floor: -pl " + last + " -am validate",
                            List.of("-pl", last, "-am", "validate"), nothing});
                } else {
                    cases.add(new Object[] {"clean verify, empty cache", concat(List.of("clean", "verify"), x), clearCache});
                    cases.add(new Object[] {"clean verify, cache off (state + strict)",
                            concat(concat(List.of("clean", "verify"), x), List.of("-Dmojoio.cache.enabled=false")), nothing});
                    cases.add(new Object[] {"clean verify, warm cache", concat(List.of("clean", "verify"), x), nothing});
                    cases.add(new Object[] {"verify, nothing changed", concat(List.of("verify"), x), nothing});
                    cases.add(new Object[] {"verify, 1 source changed in m00", concat(List.of("verify"), x), touch});
                    cases.add(new Object[] {"graph export (dry run)",
                            concat(List.of("verify", "-Dmojoio.graph.output=graph.json"), x), nothing});
                    cases.add(new Object[] {"invocation floor: -pl " + last + " -am validate",
                            concat(List.of("-pl", last, "-am", "validate"), x), nothing});
                    cases.add(new Object[] {"one task: surefire in " + last, concat(task, x), nothing});
                }
                for (Object[] c : cases) {
                    String scenario = (String) c[0];
                    @SuppressWarnings("unchecked")
                    List<String> a = (List<String>) c[1];
                    if (scenario.startsWith("one task")) {
                        run(tool, concat(List.of("test-compile"), x), proj, work.resolve("prep.log"));
                    }
                    List<Double> times = new ArrayList<>();
                    for (int r = 0; r < reps; r++) {
                        ((Runnable) c[2]).run();
                        long start = System.nanoTime();
                        run(tool, a, proj, work.resolve("run.log"));
                        times.add((System.nanoTime() - start) / 1e9);
                    }
                    double median = times.stream().sorted().toList().get(times.size() / 2);
                    rows.add(new Row(name, mode, scenario, median, times));
                    System.out.printf("%-5s %-8s %-46s %6.1fs  %s%n", name, mode, scenario, median, fmt(times, " "));
                }
            }
            stop(name, tool);
        }
        StringBuilder md = new StringBuilder("| Tool | Mode | Scenario | Median | Runs |\n|---|---|---|---|---|\n");
        for (Row r : rows) {
            md.append(String.format("| %s | %s | %s | %.1fs | %s |%n", r.tool(), r.mode(), r.scenario(), r.median(), fmt(r.runs(), " / ")));
        }
        System.out.println();
        System.out.print(md);
        if (out != null) {
            Files.writeString(Path.of(out), md);
        }
    }

    static void run(String tool, List<String> args, Path dir, Path log) throws IOException, InterruptedException {
        List<String> cmd = concat(List.of(tool, "-o", "-B", "-q"), args);
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("FAILED: " + String.join(" ", cmd) + " (see " + log + ")");
        }
    }

    static void stop(String name, String tool) throws IOException, InterruptedException {
        if (name.equals("mvnd")) {
            new ProcessBuilder(tool, "--stop").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor();
        }
    }

    static String extensionClasspath(Path work) throws IOException {
        Path root = Path.of(System.getProperty("user.dir"));
        Path frozen = Files.createDirectories(work.resolve("ext"));
        List<String> jars = new ArrayList<>();
        for (String m : List.of("mojo-io-maven-extension", "mojo-io-spec", "mojo-io-cache")) {
            try (Stream<Path> s = Files.list(root.resolve(m).resolve("target"))) {
                Path jar = s.filter(p -> p.getFileName().toString().matches(m + "-[^-]+(-SNAPSHOT)?\\.jar")).findFirst()
                        .orElseThrow(() -> new IllegalStateException("missing " + m + " jar: run `mise run build` first"));
                jars.add(Files.copy(jar, frozen.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING).toString());
            }
        }
        return String.join(":", jars);
    }

    /** A reactor of {@code modules} modules in a dependency chain, each with classes, a resource, a test, a jar. */
    static void generate(Path root, int modules, int classes) throws IOException {
        StringBuilder mods = new StringBuilder();
        for (int i = 0; i < modules; i++) {
            mods.append(String.format("    <module>m%02d</module>%n", i));
        }
        write(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>bench</groupId><artifactId>root</artifactId><version>1.0.0-SNAPSHOT</version><packaging>pom</packaging>
                  <modules>
                %s  </modules>
                  <properties><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding><maven.compiler.release>17</maven.compiler.release></properties>
                  <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.11.4</version><scope>test</scope></dependency></dependencies>
                  <build><pluginManagement><plugins>
                    <plugin><artifactId>maven-clean-plugin</artifactId><version>3.4.0</version></plugin>
                    <plugin><artifactId>maven-resources-plugin</artifactId><version>3.3.1</version></plugin>
                    <plugin><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>
                    <plugin><artifactId>maven-surefire-plugin</artifactId><version>3.5.2</version></plugin>
                    <plugin><artifactId>maven-jar-plugin</artifactId><version>3.4.2</version></plugin>
                    <plugin><artifactId>maven-install-plugin</artifactId><version>3.1.3</version></plugin>
                  </plugins></pluginManagement></build>
                </project>
                """.formatted(mods));
        for (int i = 0; i < modules; i++) {
            String m = String.format("m%02d", i);
            Path dir = root.resolve(m);
            String dep = i == 0 ? "" : String.format("<dependencies><dependency><groupId>bench</groupId>"
                    + "<artifactId>m%02d</artifactId><version>1.0.0-SNAPSHOT</version></dependency></dependencies>", i - 1);
            write(dir.resolve("pom.xml"), """
                    <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                      <parent><groupId>bench</groupId><artifactId>root</artifactId><version>1.0.0-SNAPSHOT</version></parent>
                      <artifactId>%s</artifactId>%s</project>
                    """.formatted(m, dep));
            write(dir.resolve("src/main/resources/m.properties"), "module=" + m + "\n");
            for (int c = 0; c < classes; c++) {
                StringBuilder body = new StringBuilder();
                for (int k = 0; k < 12; k++) {
                    body.append(String.format("    public static int f%d(int x) { int s = x; for (int j = 0; j < %d; j++) { s = s * 31 + j; } return s; }%n", k, k + 3));
                }
                String prev = i > 0 && c == 0 ? String.format("bench.m%02d.C0.value(n) + ", i - 1) : "";
                write(dir.resolve("src/main/java/bench/" + m + "/C" + c + ".java"), """
                        package bench.%s;
                        public final class C%d {
                        %s    public static int value(int n) { return %sf0(n) + %d; }
                        }
                        """.formatted(m, c, body, prev, c));
            }
            write(dir.resolve("src/test/java/bench/" + m + "/CTest.java"), """
                    package bench.%s;
                    import static org.junit.jupiter.api.Assertions.assertEquals;
                    import org.junit.jupiter.api.Test;
                    class CTest {
                        @Test void value() { assertEquals(C1.value(1), C1.value(1)); }
                        @Test void f() { assertEquals(C2.f3(2), C2.f3(2)); }
                    }
                    """.formatted(m));
        }
    }

    static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    static void append(Path file, String text) {
        try {
            Files.writeString(file, Files.readString(file) + text);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static void delete(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> r = new ArrayList<>(a);
        r.addAll(b);
        return r;
    }

    static String fmt(List<Double> times, String sep) {
        return String.join(sep, times.stream().map(t -> String.format("%.1f", t)).toList());
    }
}
