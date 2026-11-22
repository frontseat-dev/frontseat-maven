package dev.frontseat.mojoio.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.frontseat.mojoio.cache.AntPattern;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A minimal, Nx-shaped build system that caches Maven work using nothing but mojo-io's public
 * contract: the task graph ({@code mojo-io-task-graph} v1), the state files it names, and running one
 * task with {@code -pl <id> -am -Dmojoio.only=<id> <invocations>}. mojo-io's own cache stays off.
 *
 * <ul>
 *   <li>a task = the executions of one project bound to one phase, or sharing a {@code runTogether}
 *       group (a phase with its pre-/post- or before:/after: hooks);</li>
 *   <li>tasks and their dependencies come ready-made from the graph ({@code tasks}: runTogether groups,
 *       Maven 4 phase links), so e.g. app's compile depends on lib-core's compile, not its tests;</li>
 *   <li>task hash = fingerprints + declared file inputs + poms + external dependencies + the hashes of the
 *       tasks it depends on;</li>
 *   <li>hit = copy the stored outputs and state files back; miss = run the task, store them.</li>
 * </ul>
 */
final class ReferenceBuildSystem {

    record Outcome(String task, boolean hit) {}

    static final class TaskFailed extends RuntimeException {
        final String task;

        TaskFailed(String task, String log) {
            super("task " + task + " failed\n" + log);
            this.task = task;
        }
    }

    private record Task(String id, String path, List<JsonNode> executions, List<String> dependsOn) {}

    private final MavenBuild maven;
    private final Path root;
    private final Path cache;
    private final boolean respectRunTogether;

    ReferenceBuildSystem(MavenBuild maven, Path cache) {
        this(maven, cache, true);
    }

    /** {@code respectRunTogether=false} splits hook brackets into separate tasks (a control: it breaks). */
    ReferenceBuildSystem(MavenBuild maven, Path cache, boolean respectRunTogether) {
        this.maven = maven;
        this.root = maven.dir();
        this.cache = cache;
        this.respectRunTogether = respectRunTogether;
    }

    List<Outcome> build(String goal) throws IOException {
        maven.run(goal, "-Dmojoio.graph.output=graph.json");
        JsonNode graph = new ObjectMapper().readTree(root.resolve("graph.json").toFile());

        Map<String, String> taskHashes = new LinkedHashMap<>();
        List<Outcome> outcomes = new ArrayList<>();
        for (JsonNode project : graph.get("projects")) {
            String id = project.get("id").asText();
            for (Task task : tasks(project)) {
                Path dir = root.resolve(task.path());
                StringBuilder upstream = new StringBuilder();
                for (String dep : task.dependsOn()) {
                    upstream.append(dep).append('=').append(taskHashes.get(dep)).append(';');
                }
                String hash = hash(task, project, dir, upstream.toString());
                boolean cacheable = task.executions().stream().allMatch(e -> e.get("cacheable").asBoolean());
                Path entry = cache.resolve(hash);
                if (cacheable && Files.isDirectory(entry)) {
                    copyTree(entry, dir);
                    outcomes.add(new Outcome(task.id(), true));
                } else {
                    List<String> args = new ArrayList<>(List.of("-pl", id, "-am", "-Dmojoio.only=" + id,
                            "-Dmojoio.cache.enabled=false", "-Dmojoio.prerequisites=false"));
                    task.executions().forEach(e -> args.add(e.get("invocation").asText()));
                    MavenBuild.Result result = maven.runAllowingFailure(args.toArray(String[]::new));
                    if (result.exitCode() != 0) {
                        throw new TaskFailed(task.id(), result.log());
                    }
                    if (cacheable) {
                        store(task, dir, entry);
                    }
                    outcomes.add(new Outcome(task.id(), false));
                }
                taskHashes.put(task.id(), hash);
            }
        }
        return outcomes;
    }

    /**
     * The graph's ready-made tasks (runTogether groups, Maven 4 phase links as dependsOn). The control
     * mode splits each task into one task per phase, chained, as a naive orchestrator would.
     */
    private List<Task> tasks(JsonNode project) {
        Map<String, JsonNode> executions = new LinkedHashMap<>();
        project.get("executions").forEach(e -> executions.put(e.get("id").asText(), e));
        List<Task> result = new ArrayList<>();
        for (JsonNode t : project.get("tasks")) {
            List<String> dependsOn = new ArrayList<>(texts(t.get("dependsOn")));
            List<JsonNode> members = new ArrayList<>();
            t.get("executions").forEach(x -> members.add(executions.get(x.asText())));
            if (respectRunTogether) {
                result.add(new Task(t.get("id").asText(), project.get("path").asText(), members, dependsOn));
                continue;
            }
            Map<String, List<JsonNode>> byPhase = new LinkedHashMap<>();
            members.forEach(m -> byPhase.computeIfAbsent(m.get("canonicalPhase").asText(), k -> new ArrayList<>()).add(m));
            for (Map.Entry<String, List<JsonNode>> e : byPhase.entrySet()) {
                String id = project.get("id").asText() + ":" + e.getKey();
                result.add(new Task(id, project.get("path").asText(), e.getValue(), dependsOn));
                dependsOn = List.of(id);
            }
        }
        return result;
    }

    private String hash(Task task, JsonNode project, Path dir, String upstream) throws IOException {
        StringBuilder sb = new StringBuilder(task.id()).append("|deps=").append(upstream);
        for (JsonNode pom : project.get("poms")) {
            sb.append("|pom=").append(contentHash(resolve(pom.asText(), dir), List.of(), List.of()));
        }
        sb.append("|ext=").append(project.get("externalDependencies"));
        for (JsonNode e : task.executions()) {
            sb.append("|exec=").append(e.get("invocation").asText()).append(' ').append(e.get("fingerprint"));
            for (JsonNode in : e.get("inputs")) {
                switch (in.get("kind").asText()) {
                    case "files" -> {
                        for (JsonNode p : in.get("paths")) {
                            sb.append("|f=").append(contentHash(resolve(p.asText(), dir), texts(in.get("includes")),
                                    texts(in.get("excludes"))));
                        }
                    }
                    case "classpath" -> { } // covered by the task's dependsOn (upstream task hashes)
                    default -> sb.append("|v=").append(in.get("value"));
                }
            }
        }
        return sha256(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void store(Task task, Path dir, Path entry) throws IOException {
        Path tmp = entry.resolveSibling(entry.getFileName() + ".tmp");
        for (JsonNode e : task.executions()) {
            Path state = resolve(e.get("stateFile").asText(), dir);
            for (JsonNode out : e.get("outputs")) {
                if (out.path("observed").asBoolean(false)) {
                    // Known only now: the state file lists exactly what was written.
                    if (Files.isRegularFile(state)) {
                        for (dev.frontseat.mojoio.spec.StateChange c : dev.frontseat.mojoio.spec.StateFile.read(state).changes()) {
                            if (c.kind().equals("output")) {
                                Path f = Path.of(c.value().replace("@@BASEDIR@@", dir.toString()));
                                if (Files.isRegularFile(f)) {
                                    copy(f, tmp.resolve(dir.relativize(f)));
                                }
                            }
                        }
                    }
                    continue;
                }
                Path p = resolve(out.get("path").asText(), dir);
                for (Path f : files(p, texts(out.get("includes")), texts(out.get("excludes")))) {
                    copy(f, tmp.resolve(dir.relativize(f)));
                }
            }
            if (Files.isRegularFile(state)) {
                copy(state, tmp.resolve(dir.relativize(state)));
            }
        }
        Files.createDirectories(tmp);
        Files.move(tmp, entry, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path resolve(String path, Path projectDir) {
        return Path.of(path.replace("{projectRoot}", projectDir.toString())
                .replace("{workspaceRoot}", root.toString())).normalize();
    }

    private static String contentHash(Path p, List<String> includes, List<String> excludes) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Path f : files(p, includes, excludes)) {
            sb.append(p.relativize(f)).append('=').append(sha256(Files.readAllBytes(f))).append(';');
        }
        return sb.length() == 0 ? "absent" : sha256(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<Path> files(Path p, List<String> includes, List<String> excludes) throws IOException {
        if (Files.isRegularFile(p)) {
            return List.of(p);
        }
        if (!Files.isDirectory(p)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(p)) {
            return s.filter(Files::isRegularFile)
                    .filter(f -> AntPattern.selected(p.relativize(f).toString().replace('\\', '/'), includes, excludes))
                    .sorted().toList();
        }
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null) {
            array.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    private static void copy(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                copy(f, to.resolve(from.relativize(f)));
            }
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }
}
