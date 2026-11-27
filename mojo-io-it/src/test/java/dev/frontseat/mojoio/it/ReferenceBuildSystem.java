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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A minimal, Nx-shaped build system that caches Maven work using nothing but mojo-io's public
 * contract: the task graph ({@code mojo-io-task-graph} v1), the state files it names, and running one
 * task with {@code -pl <id> -am -Dmojoio.only=<id> <invocations>}.
 *
 * <ul>
 *   <li>tasks and their dependencies come ready-made from the graph ({@code tasks}: runTogether groups,
 *       Maven 4 phase links), so e.g. app's compile depends on lib-core's compile, not its tests;</li>
 *   <li>task hash = fingerprints + declared file inputs + poms + external dependencies + the hashes of the
 *       tasks it depends on;</li>
 *   <li>a task runs in a fresh sandbox: the checkout without any {@code target/}, plus the outputs of the
 *       tasks it depends on, transitively. Afterwards the sandbox is diffed: every file a cacheable task
 *       wrote must be a declared output, under an observed output, an ignored write or one of its state
 *       files, or the task fails;</li>
 *   <li>hit = copy the stored outputs into the checkout; miss = run the task, store its outputs and copy
 *       them into the checkout.</li>
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
    private final String granularity;
    private int sandboxes;

    ReferenceBuildSystem(MavenBuild maven, Path cache) {
        this(maven, cache, true);
    }

    /** {@code granularity}: the graph's task size, {@code phase}, {@code stage} or {@code module}. */
    ReferenceBuildSystem(MavenBuild maven, Path cache, String granularity) {
        this.maven = maven;
        this.root = maven.dir();
        this.cache = cache;
        this.respectRunTogether = true;
        this.granularity = granularity;
    }

    /** {@code respectRunTogether=false} splits hook brackets into separate tasks (a control: it breaks). */
    ReferenceBuildSystem(MavenBuild maven, Path cache, boolean respectRunTogether) {
        this.maven = maven;
        this.root = maven.dir();
        this.cache = cache;
        this.respectRunTogether = respectRunTogether;
        this.granularity = "phase";
    }

    List<Outcome> build(String goal) throws IOException {
        maven.run(goal, "-Dmojoio.graph.output=graph.json", "-Dmojoio.graph.granularity=" + granularity);
        JsonNode graph = new ObjectMapper().readTree(root.resolve("graph.json").toFile());

        Map<String, String> taskHashes = new LinkedHashMap<>();
        Map<String, List<String>> taskDependsOn = new LinkedHashMap<>();
        // Where each task's outputs are, for the sandboxes of the tasks after it.
        Map<String, Path> taskOutputs = new LinkedHashMap<>();
        List<Outcome> outcomes = new ArrayList<>();
        for (JsonNode project : referencedFirst(graph)) {
            String id = project.get("id").asText();
            for (Task task : tasks(project)) {
                Path dir = root.resolve(task.path()).normalize();
                StringBuilder upstream = new StringBuilder();
                for (String dep : task.dependsOn()) {
                    upstream.append(dep).append('=').append(taskHashes.get(dep)).append(';');
                }
                String hash = hash(task, project, dir, upstream.toString());
                taskHashes.put(task.id(), hash);
                taskDependsOn.put(task.id(), task.dependsOn());
                boolean cacheable = task.executions().stream().allMatch(e -> e.get("cacheable").asBoolean());
                Path entry = cache.resolve(hash);
                if (cacheable && Files.isDirectory(entry)) {
                    copyTree(entry, root);
                    taskOutputs.put(task.id(), entry);
                    outcomes.add(new Outcome(task.id(), true));
                    continue;
                }
                Path sandbox = sandbox(task, taskDependsOn, taskOutputs);
                Map<Path, String> before = snapshot(sandbox);
                List<String> projects = new ArrayList<>(List.of(id));
                project.path("reactorReferences").forEach(r -> projects.add(r.asText()));
                List<String> args = new ArrayList<>(List.of("-pl", String.join(",", projects), "-am",
                        "-Dmojoio.only=" + id, "-Dmojoio.cache.enabled=false"));
                task.executions().forEach(e -> args.add(e.get("invocation").asText()));
                MavenBuild.Result result = maven.at(sandbox).runAllowingFailure(args.toArray(String[]::new));
                if (result.exitCode() != 0) {
                    throw new TaskFailed(task.id(), result.log());
                }
                Path outputs = cacheable ? entry : cache.resolveSibling(cache.getFileName() + "-uncacheable").resolve(hash);
                store(task, sandbox, before, cacheable, outputs);
                copyTree(outputs, root);
                taskOutputs.put(task.id(), outputs);
                outcomes.add(new Outcome(task.id(), false));
            }
        }
        return outcomes;
    }

    /** A fresh input root: the checkout without its build directories, plus the outputs of the tasks it depends on. */
    private Path sandbox(Task task, Map<String, List<String>> dependsOn, Map<String, Path> outputs) throws IOException {
        Path sandbox = cache.resolveSibling(cache.getFileName() + "-sandboxes").resolve(String.valueOf(++sandboxes));
        MavenBuild.deleteTree(sandbox);
        try (Stream<Path> s = Files.walk(root)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                Path rel = root.relativize(f);
                boolean built = false;
                for (Path part : rel) {
                    built |= part.toString().equals("target");
                }
                if (!built && !rel.toString().equals("graph.json")) {
                    copy(f, sandbox.resolve(rel));
                }
            }
        }
        Set<String> upstream = new LinkedHashSet<>();
        List<String> queue = new ArrayList<>(task.dependsOn());
        while (!queue.isEmpty()) {
            String t = queue.remove(0);
            if (upstream.add(t)) {
                queue.addAll(dependsOn.getOrDefault(t, List.of()));
            }
        }
        for (String t : upstream) {
            copyTree(outputs.get(t), sandbox);
        }
        return sandbox;
    }

    /**
     * Copies what the task wrote in {@code sandbox} to {@code entry}. Every write of a cacheable task must be
     * declared; an uncacheable task's writes are all kept, for the tasks after it.
     */
    private void store(Task task, Path sandbox, Map<Path, String> before, boolean cacheable, Path entry)
            throws IOException {
        Path dir = sandbox.resolve(task.path()).normalize();
        Path buildDir = dir.resolve("target");
        Path tmp = entry.resolveSibling(entry.getFileName() + ".tmp");
        MavenBuild.deleteTree(tmp);
        Files.createDirectories(tmp);
        List<String> undeclared = new ArrayList<>();
        for (Map.Entry<Path, String> f : snapshot(sandbox).entrySet()) {
            if (f.getValue().equals(before.get(f.getKey())) || ignored(task, buildDir, f.getKey())) {
                continue;
            }
            if (!cacheable || declared(task, dir, f.getKey())) {
                copy(f.getKey(), tmp.resolve(sandbox.relativize(f.getKey())));
            } else {
                undeclared.add(sandbox.relativize(f.getKey()).toString());
            }
        }
        if (!undeclared.isEmpty()) {
            throw new TaskFailed(task.id(), "wrote files outside its declared outputs: " + undeclared);
        }
        MavenBuild.deleteTree(entry);
        Files.createDirectories(entry.getParent());
        Files.move(tmp, entry, StandardCopyOption.REPLACE_EXISTING);
    }

    /** A state file of the task's executions, or under one of their declared or observed outputs. */
    private boolean declared(Task task, Path dir, Path f) {
        for (JsonNode e : task.executions()) {
            if (f.equals(resolve(e.get("stateFile").asText(), dir))) {
                return true;
            }
            for (JsonNode out : e.get("outputs")) {
                Path p = resolve(out.get("path").asText(), dir);
                if (f.equals(p) || f.startsWith(p) && AntPattern.selected(relative(p, f),
                        texts(out.get("includes")), texts(out.get("excludes")))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Bookkeeping the graph says is no output: an ignored write (relative to the build directory). */
    private static boolean ignored(Task task, Path buildDir, Path f) {
        if (!f.startsWith(buildDir)) {
            return false;
        }
        String rel = relative(buildDir, f);
        for (JsonNode e : task.executions()) {
            for (String pattern : texts(e.get("ignoredWrites"))) {
                if (AntPattern.compile(pattern).matches(rel)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Size and modification time of every file under {@code dir}. */
    private static Map<Path, String> snapshot(Path dir) throws IOException {
        Map<Path, String> files = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path f : s.filter(Files::isRegularFile).sorted().toList()) {
                files.put(f, Files.size(f) + ":" + Files.getLastModifiedTime(f).toMillis());
            }
        }
        return files;
    }

    /** The graph's projects, each after the reactor projects it references (Maven's order does not know them all). */
    private static List<JsonNode> referencedFirst(JsonNode graph) {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        graph.get("projects").forEach(p -> byId.put(p.get("id").asText(), p));
        List<JsonNode> order = new ArrayList<>();
        Set<String> visited = new java.util.HashSet<>();
        for (String id : byId.keySet()) {
            visit(id, byId, visited, order);
        }
        return order;
    }

    private static void visit(String id, Map<String, JsonNode> byId, Set<String> visited, List<JsonNode> order) {
        JsonNode p = byId.get(id);
        if (p == null || !visited.add(id)) {
            return;
        }
        p.path("reactorReferences").forEach(r -> visit(r.asText(), byId, visited, order));
        order.add(p);
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
        // Build outputs are not hashed as files: they do not exist yet when the task is hashed on a fresh
        // checkout, and the hashes of the tasks that make them stand for them.
        Path buildDir = dir.resolve("target");
        for (JsonNode e : task.executions()) {
            sb.append("|exec=").append(e.get("invocation").asText()).append(' ').append(e.get("fingerprint"));
            for (JsonNode in : e.get("inputs")) {
                switch (in.get("kind").asText()) {
                    case "files" -> {
                        for (JsonNode p : in.get("paths")) {
                            Path path = resolve(p.asText(), dir);
                            if (path.startsWith(buildDir)) {
                                continue;
                            }
                            sb.append("|f=").append(contentHash(path, texts(in.get("includes")),
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

    private Path resolve(String path, Path projectDir) {
        return Path.of(path.replace("{projectRoot}", projectDir.toString())
                .replace("{workspaceRoot}", root.toString())).normalize();
    }

    private static String relative(Path base, Path f) {
        return base.relativize(f).toString().replace('\\', '/');
    }

    private static String contentHash(Path p, List<String> includes, List<String> excludes) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Path f : files(p, includes, excludes)) {
            sb.append(relative(p, f)).append('=').append(sha256(Files.readAllBytes(f))).append(';');
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
                    .filter(f -> AntPattern.selected(relative(p, f), includes, excludes))
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
