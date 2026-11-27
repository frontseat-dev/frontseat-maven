package dev.frontseat.mojoio.reapi;

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.ActionCacheGrpc;
import build.bazel.remote.execution.v2.GetActionResultRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Command;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.ExecuteRequest;
import build.bazel.remote.execution.v2.ExecuteResponse;
import build.bazel.remote.execution.v2.ExecutionGrpc;
import build.bazel.remote.execution.v2.FileNode;
import build.bazel.remote.execution.v2.OutputDirectory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.longrunning.Operation;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import dev.frontseat.mojoio.cache.AntPattern;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Builds a Maven workspace on a Remote Execution API (REAPI) service such as Buildbarn, using only
 * mojo-io's tool-agnostic contract: the task graph, the state files and single-task invocations.
 *
 * <p>Each graph task is one REAPI action:
 * <ul>
 *   <li><b>Command:</b> {@code mvn -pl <id> -am -Dmojoio.only=<id> <invocations>}, plus a fingerprint of
 *       what is not in the input root (evaluated configuration, plugin versions, JDK, external
 *       dependencies).</li>
 *   <li><b>Input root:</b> {@code .mvn/}, every reactor pom, the task's declared file inputs, and the
 *       {@code target/} directories produced by the tasks it depends on. Those are mounted by digest,
 *       straight from the upstream action results: nothing is downloaded in between.</li>
 *   <li><b>Output:</b> the project's {@code target/} directory, which includes mojo-io's state files.</li>
 * </ul>
 * REAPI derives the action's key from command and input root, so a task whose inputs did not change is
 * an action-cache hit on any machine. Maven, the JDK and the local repository are tools of the worker.
 */
public final class ReapiBuild {

    record Options(Path workspace, String executor, String instance, String goal, String granularity, String maven,
                   String localMaven, int parallel, boolean download, Map<String, String> env, List<String> mavenArgs,
                   Path report) {}

    record FileEntry(Path path, Digest digest, boolean executable, long size, long mtime) {}

    /** A directory of the input root being assembled; {@code mounted} is a directory already in the CAS. */
    static final class Dir {
        final TreeMap<String, Dir> dirs = new TreeMap<>();
        final TreeMap<String, FileEntry> files = new TreeMap<>();
        Digest mounted;

        Dir dir(String relative) {
            Dir d = this;
            for (String part : relative.split("/")) {
                if (!part.isEmpty()) {
                    d = d.dirs.computeIfAbsent(part, k -> new Dir());
                }
            }
            return d;
        }
    }

    /** What a finished task left behind: its project's target directory, as a CAS directory and Tree. */
    record TaskResult(String id, boolean cached, double seconds, Digest targetRoot, Digest targetTree) {}

    record Task(String id, String projectId, String projectPath, List<String> dependsOn, List<JsonNode> executions,
                boolean cacheable) {}

    private final Options options;
    private final Cas cas;
    private final ExecutionGrpc.ExecutionBlockingStub execution;
    private final ActionCacheGrpc.ActionCacheBlockingStub actionCache;
    private final Map<Path, FileEntry> fileMemo = new ConcurrentHashMap<>();
    private final Set<Digest> knownDirectories = ConcurrentHashMap.newKeySet();

    ReapiBuild(Options options, ManagedChannel channel) {
        this.options = options;
        this.cas = new Cas(channel, options.instance());
        this.execution = ExecutionGrpc.newBlockingStub(channel);
        this.actionCache = ActionCacheGrpc.newBlockingStub(channel);
    }

    public static void main(String[] args) throws Exception {
        Options o = parse(args);
        String[] hostPort = o.executor().split(":");
        ManagedChannel channel = ManagedChannelBuilder.forAddress(hostPort[0], Integer.parseInt(hostPort[1]))
                .usePlaintext().maxInboundMessageSize(64 * 1024 * 1024).build();
        try {
            System.exit(new ReapiBuild(o, channel).run() ? 0 : 1);
        } finally {
            channel.shutdownNow();
        }
    }

    /**
     * Tasks with every task they depend on first. The graph lists projects in Maven's reactor order,
     * which does not know about projects referenced from configuration (a Tycho target definition).
     */
    static List<Task> dependencyOrder(Map<String, Task> tasks) {
        List<Task> order = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        for (String id : tasks.keySet()) {
            visit(id, tasks, visited, order);
        }
        return order;
    }

    private static void visit(String id, Map<String, Task> tasks, Set<String> visited, List<Task> order) {
        Task task = tasks.get(id);
        if (task == null || !visited.add(id)) {
            return;
        }
        for (String dep : task.dependsOn()) {
            visit(dep, tasks, visited, order);
        }
        order.add(task);
    }

    /**
     * The task's project and the reactor projects it needs that Maven's "-am" can miss: named in
     * configuration, or added as dependencies while loading (Eclipse Tycho).
     */
    static String projectList(JsonNode project) {
        List<String> ids = new ArrayList<>(List.of(project.get("id").asText()));
        project.path("reactorReferences").forEach(r -> ids.add(r.asText()));
        return String.join(",", ids);
    }

    boolean run() throws Exception {
        long start = System.nanoTime();
        JsonNode graph = exportGraph();
        Map<String, Task> tasks = new LinkedHashMap<>();
        Map<String, JsonNode> projects = new LinkedHashMap<>();
        for (JsonNode p : graph.get("projects")) {
            projects.put(p.get("id").asText(), p);
            Map<String, JsonNode> executions = new LinkedHashMap<>();
            p.get("executions").forEach(e -> executions.put(e.get("id").asText(), e));
            for (JsonNode t : p.get("tasks")) {
                List<JsonNode> members = new ArrayList<>();
                t.get("executions").forEach(x -> members.add(executions.get(x.asText())));
                List<String> dependsOn = new ArrayList<>();
                t.get("dependsOn").forEach(d -> dependsOn.add(d.asText()));
                tasks.put(t.get("id").asText(), new Task(t.get("id").asText(), p.get("id").asText(),
                        p.get("path").asText(), dependsOn, members,
                        members.stream().allMatch(e -> e.get("cacheable").asBoolean())));
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(options.parallel());
        Map<String, CompletableFuture<TaskResult>> futures = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
        for (Task task : dependencyOrder(tasks)) {
            List<CompletableFuture<TaskResult>> deps = task.dependsOn().stream().map(futures::get)
                    .filter(java.util.Objects::nonNull).toList();
            futures.put(task.id(), CompletableFuture.allOf(deps.toArray(CompletableFuture[]::new))
                    .thenApplyAsync(v -> execute(task, tasks, projects, graph, futures), pool));
        }
        List<TaskResult> results = new ArrayList<>();
        boolean ok = true;
        for (CompletableFuture<TaskResult> f : new ArrayList<>(futures.values())) {
            try {
                results.add(f.get());
            } catch (java.util.concurrent.ExecutionException e) {
                if (ok) {
                    System.err.println(rootCause(e).getMessage());
                }
                ok = false;
            }
        }
        pool.shutdown();
        if (ok && options.download()) {
            download(tasks, results);
        }
        long executed = results.stream().filter(r -> !r.cached()).count();
        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf("%s: %d task(s), %d executed remotely, %d from the action cache, %.1fs%n",
                ok ? "BUILD SUCCESS" : "BUILD FAILURE", results.size(), executed, results.size() - executed, seconds);
        if (options.report() != null) {
            ObjectNode report = new ObjectMapper().createObjectNode();
            report.put("success", ok).put("seconds", seconds).put("tasks", results.size()).put("executed", executed);
            ArrayNode list = report.putArray("results");
            results.forEach(r -> list.addObject().put("id", r.id()).put("cached", r.cached()).put("seconds", r.seconds()));
            Files.writeString(options.report(), report.toPrettyString());
        }
        return ok;
    }

    private static Throwable rootCause(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private JsonNode exportGraph() throws IOException, InterruptedException {
        Path out = Files.createTempFile("mojo-io-graph", ".json");
        List<String> command = new ArrayList<>(List.of(options.localMaven(), "-B", "-q", options.goal(),
                "-Dmojoio.graph.output=" + out, "-Dmojoio.graph.granularity=" + options.granularity()));
        command.addAll(options.mavenArgs());
        Process p = new ProcessBuilder(command).directory(options.workspace().toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("graph export failed: " + String.join(" ", command));
        }
        JsonNode graph = new ObjectMapper().readTree(out.toFile());
        Files.deleteIfExists(out);
        return graph;
    }

    private TaskResult execute(Task task, Map<String, Task> tasks, Map<String, JsonNode> projects, JsonNode graph,
                               Map<String, CompletableFuture<TaskResult>> futures) {
        long start = System.nanoTime();
        try {
            Path root = options.workspace();
            Dir input = new Dir();
            addTree(input, root, root.resolve(".mvn"), List.of(), List.of());
            for (JsonNode p : graph.get("projects")) {
                addFile(input, root, root.resolve(p.get("path").asText()).resolve("pom.xml"));
                for (JsonNode pom : p.get("poms")) {
                    addFile(input, root, resolve(pom.asText(), root, root.resolve(p.get("path").asText())));
                }
            }
            JsonNode project = projects.get(task.projectId());
            Path projectDir = root.resolve(task.projectPath()).normalize();
            // What this client cannot carry between actions. Such a task still runs, but is never cached.
            List<String> unsupported = new ArrayList<>();
            for (JsonNode e : task.executions()) {
                for (JsonNode out : e.get("outputs")) {
                    Path path = resolve(out.get("path").asText(), root, projectDir);
                    if (!path.startsWith(projectDir.resolve("target"))) {
                        unsupported.add("writes " + path + ", outside the project's target directory");
                    }
                }
            }
            if (task.cacheable()) {
                for (JsonNode e : task.executions()) {
                    for (JsonNode in : e.get("inputs")) {
                        if (!in.get("kind").asText().equals("files")) {
                            continue;
                        }
                        for (JsonNode p : in.get("paths")) {
                            if (p.asText().contains("{localRepository}")) {
                                continue; // a tool of the worker
                            }
                            Path path = resolve(p.asText(), root, projectDir);
                            if (!path.startsWith(root)) {
                                if (Files.exists(path)) {
                                    unsupported.add("reads " + path + ", outside the workspace");
                                }
                            } else if (!inBuildDirectory(root.relativize(path))) {
                                addTree(input, root, path, texts(in.get("includes")), texts(in.get("excludes")));
                            }
                        }
                    }
                }
            } else {
                // Some mojo of the task has no declaration: anything in the project or its ancestors may
                // be an input.
                Set<Path> moduleDirs = new LinkedHashSet<>();
                graph.get("projects").forEach(p -> moduleDirs.add(root.resolve(p.get("path").asText()).normalize()));
                addModule(input, root, projectDir, moduleDirs);
                for (JsonNode pom : project.get("poms")) {
                    addModule(input, root, resolve(pom.asText(), root, projectDir).getParent(), moduleDirs);
                }
            }

            // What build extensions read while Maven loads the projects of this invocation, before any
            // mojo runs (Tycho: manifests, feature.xml, the target definition): this project's and
            // those of the reactor projects it loads besides.
            List<JsonNode> loaded = new ArrayList<>(List.of(project));
            project.path("reactorReferences").forEach(r -> {
                if (projects.containsKey(r.asText())) {
                    loaded.add(projects.get(r.asText()));
                }
            });
            for (JsonNode p : loaded) {
                Path dir = root.resolve(p.get("path").asText());
                for (JsonNode f : p.path("loadFiles")) {
                    addFile(input, root, resolve(f.asText(), root, dir));
                }
            }

            // Upstream outputs: the target directory of the latest task per project among everything this
            // task depends on, mounted by digest.
            Map<String, TaskResult> latest = new LinkedHashMap<>();
            Set<String> seen = new LinkedHashSet<>();
            List<String> queue = new ArrayList<>(task.dependsOn());
            for (int i = 0; i < queue.size(); i++) {
                String id = queue.get(i);
                if (!seen.add(id) || !tasks.containsKey(id)) {
                    continue;
                }
                queue.addAll(tasks.get(id).dependsOn());
            }
            for (Task t : tasks.values()) { // graph order: a project's later task overrides its earlier one
                if (seen.contains(t.id())) {
                    latest.put(t.projectId(), futures.get(t.id()).join());
                }
            }
            boolean ownTarget = false;
            List<String> mountedTargets = new ArrayList<>();
            for (Map.Entry<String, TaskResult> e : latest.entrySet()) {
                if (e.getValue().targetRoot() == null) {
                    continue;
                }
                String path = projects.get(e.getKey()).get("path").asText();
                input.dir(path).dir("target").mounted = e.getValue().targetRoot();
                mountedTargets.add(path.isEmpty() ? "target" : path + "/target");
                ownTarget |= e.getKey().equals(task.projectId());
            }

            Map<Digest, Supplier<byte[]>> blobs = new LinkedHashMap<>();
            Digest inputRoot = build(input, blobs);

            String target = task.projectPath().isEmpty() ? "target" : task.projectPath() + "/target";
            List<String> maven = new ArrayList<>(List.of(options.maven(), "-B", "--no-transfer-progress", "-pl",
                    projectList(project), "-am", "-Dmojoio.only=" + task.projectId(), "-Dmojoio.cache.enabled=false", "-Dmojoio.fingerprint=" + fingerprint(task, project)));
            maven.addAll(options.mavenArgs());
            task.executions().forEach(e -> maven.add(e.get("invocation").asText()));
            // The action runs through a small shell wrapper ($0 is the target directory):
            // - Inputs are immutable (workers hardlink them from a shared cache), but a task writes into
            //   the target directory an earlier task of its project produced: work on a copy.
            // - Bookkeeping the mojos write (the graph's ignoredWrites, e.g. maven-status/ with absolute
            //   paths of this worker) is removed, so equal inputs give byte-identical outputs and tasks
            //   downstream of a re-run that changed nothing stay cached.
            StringBuilder script = new StringBuilder();
            if (ownTarget) {
                script.append("cp -R \"$0\" \"$0.mojoio\" && chmod -R u+w \"$0.mojoio\" && rm -rf \"$0\""
                        + " && mv \"$0.mojoio\" \"$0\" || exit 1; ");
            }
            // - Tools write absolute paths into the target directory (Tycho's local-artifacts.properties
            //   names the module's p2 repository files). Every action has its own sandbox directory, so
            //   outputs carry a token in its place, put back for the sandbox that reads them. Files are
            //   replaced, not edited: inputs are hardlinks into the worker's cache.
            for (String mounted : mountedTargets) {
                script.append(relocate(mounted, "$T", "$PWD"));
            }
            script.append("\"$@\"; rc=$?; ");
            script.append(relocate("$0", "$PWD", "$T"));
            Set<String> bookkeeping = new java.util.TreeSet<>();
            task.executions().forEach(e -> bookkeeping.addAll(texts(e.get("ignoredWrites"))));
            for (String pattern : bookkeeping) {
                if (!pattern.matches("[A-Za-z0-9_.*/-]+") || pattern.contains("..")) {
                    continue;
                }
                script.append(pattern.endsWith("/**")
                        ? "rm -rf \"$0\"/" + pattern.substring(0, pattern.length() - 3) + "; "
                        : "rm -f \"$0\"/" + pattern + "; ");
            }
            script.append("exit $rc");
            List<String> arguments = new ArrayList<>(List.of("/bin/sh", "-c", "T=" + SANDBOX_TOKEN + "; " + script, target));
            arguments.addAll(maven);
            Command.Builder command = Command.newBuilder().addAllArguments(arguments).addOutputPaths(target);
            new TreeMap<>(options.env()).forEach((k, v) -> command.addEnvironmentVariables(
                    Command.EnvironmentVariable.newBuilder().setName(k).setValue(v)));
            byte[] commandBytes = command.build().toByteArray();
            Digest commandDigest = Cas.digest(commandBytes);
            blobs.put(commandDigest, () -> commandBytes);
            // A task with an undeclared mojo may have effects outside its inputs: always run it.
            byte[] actionBytes = Action.newBuilder().setCommandDigest(commandDigest).setInputRootDigest(inputRoot)
                    .setDoNotCache(!task.cacheable() || !unsupported.isEmpty()).build().toByteArray();
            if (!unsupported.isEmpty()) {
                System.out.println("WARN  " + task.id() + " is not cached and may be incomplete for later tasks: it "
                        + String.join("; it ", new LinkedHashSet<>(unsupported)));
            }
            Digest actionDigest = Cas.digest(actionBytes);
            blobs.put(actionDigest, () -> actionBytes);
            cas.upload(blobs);

            // Like Bazel: ask the action cache first. Services such as Buildbarn leave that to the client.
            ActionResult result = task.cacheable() && unsupported.isEmpty() ? cached(actionDigest) : null;
            boolean fromCache = result != null;
            if (result == null) {
                result = executeRemotely(task, actionDigest);
            }
            Digest targetRoot = null;
            Digest targetTree = null;
            for (OutputDirectory out : result.getOutputDirectoriesList()) {
                if (out.getPath().equals(target)) {
                    targetTree = out.getTreeDigest();
                    targetRoot = publishDirectories(targetTree);
                }
            }
            double seconds = (System.nanoTime() - start) / 1e9;
            System.out.printf("%-5s %s (%.1fs)%n", fromCache ? "HIT" : "EXEC", task.id(), seconds);
            return new TaskResult(task.id(), fromCache, seconds, targetRoot, targetTree);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ActionResult cached(Digest actionDigest) {
        try {
            return actionCache.getActionResult(GetActionResultRequest.newBuilder().setInstanceName(options.instance())
                    .setActionDigest(actionDigest).build());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return null;
            }
            throw e;
        }
    }

    private ActionResult executeRemotely(Task task, Digest actionDigest) throws IOException {
        return executeRemotely(task, actionDigest, true);
    }

    private ActionResult executeRemotely(Task task, Digest actionDigest, boolean retry) throws IOException {
        Iterator<Operation> operations = execution.execute(ExecuteRequest.newBuilder()
                .setInstanceName(options.instance()).setActionDigest(actionDigest).setSkipCacheLookup(true).build());
        Operation last = null;
        while (operations.hasNext()) {
            last = operations.next();
        }
        if (last == null || !last.getDone()) {
            throw new IllegalStateException("task " + task.id() + ": execution did not complete");
        }
        if (last.hasError()) {
            throw new IllegalStateException("task " + task.id() + ": " + last.getError().getMessage());
        }
        ExecuteResponse response = last.getResponse().unpack(ExecuteResponse.class);
        if (response.getStatus().getCode() != 0) {
            throw new IllegalStateException("task " + task.id() + ": " + response.getStatus().getMessage());
        }
        ActionResult result = response.getResult();
        if (result.getExitCode() != 0) {
            byte[] stdout = result.hasStdoutDigest() && result.getStdoutDigest().getSizeBytes() > 0
                    ? cas.read(result.getStdoutDigest()) : result.getStdoutRaw().toByteArray();
            byte[] stderr = result.hasStderrDigest() && result.getStderrDigest().getSizeBytes() > 0
                    ? cas.read(result.getStderrDigest()) : result.getStderrRaw().toByteArray();
            // The extension's own failure for a build that ended without building the selected project
            // (a rare daemon race): unlike a failing mojo, worth one retry.
            if (retry && (new String(stdout, StandardCharsets.UTF_8) + new String(stderr, StandardCharsets.UTF_8))
                    .contains("[mojo-io] NOT BUILT")) {
                System.out.println("RETRY " + task.id() + " (the build ended without running it)");
                return executeRemotely(task, actionDigest, false);
            }
            throw new IllegalStateException("task " + task.id() + " failed with exit code " + result.getExitCode()
                    + "\n" + new String(stdout, StandardCharsets.UTF_8) + new String(stderr, StandardCharsets.UTF_8));
        }
        return result;
    }

    /** Stands for the action's sandbox directory in the text files of its outputs. */
    static final String SANDBOX_TOKEN = "@@MOJOIO_SANDBOX@@";

    /**
     * Shell commands replacing {@code from} by {@code to} in the text files under {@code dir} that
     * contain it. Each file is replaced by a new one.
     */
    static String relocate(String dir, String from, String to) {
        String d = dir.startsWith("$") ? "\"" + dir + "\"" : "'" + dir + "'";
        return "[ -d " + d + " ] && grep -rlIF \"" + from + "\" " + d + " | while IFS= read -r f; do"
                + " sed \"s#" + from + "#" + to + "#g\" \"$f\" > \"$f.mojoio-tmp\" && mv -f \"$f.mojoio-tmp\" \"$f\";"
                + " done; ";
    }

    /** What the input root cannot show: evaluated configuration, plugin versions, JDK, external dependencies. */
    private static String fingerprint(Task task, JsonNode project) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode e : task.executions()) {
            sb.append(e.get("fingerprint").isNull() ? e.get("id").asText() : e.get("fingerprint").asText()).append('\n');
        }
        sb.append(project.get("externalDependencies"));
        return Cas.digest(sb.toString().getBytes(StandardCharsets.UTF_8)).getHash();
    }

    /**
     * An output directory arrives as one Tree blob. To mount it in another action's input root, each of
     * its directories must be a CAS blob of its own: upload them (byte for byte as the worker wrote
     * them, so the digests it computed still hold) and return the root's digest.
     */
    private Digest publishDirectories(Digest treeDigest) throws IOException {
        byte[] tree = cas.read(treeDigest);
        Map<Digest, Supplier<byte[]>> blobs = new LinkedHashMap<>();
        Digest root = null;
        CodedInputStream in = CodedInputStream.newInstance(tree);
        while (!in.isAtEnd()) {
            int tag = in.readTag();
            int field = WireFormat.getTagFieldNumber(tag);
            if (field == 1 || field == 2) {
                byte[] raw = in.readByteArray();
                Digest d = Cas.digest(raw);
                if (field == 1) {
                    root = d;
                }
                if (!knownDirectories.contains(d)) {
                    blobs.put(d, () -> raw);
                }
            } else {
                in.skipField(tag);
            }
        }
        // Known only once uploaded: another thread may mount the same directory (the empty one is in
        // almost every tree) and start its action right away.
        cas.upload(blobs);
        knownDirectories.addAll(blobs.keySet());
        return root;
    }

    private Digest build(Dir dir, Map<Digest, Supplier<byte[]>> blobs) {
        if (dir.mounted != null) {
            return dir.mounted;
        }
        Directory.Builder d = Directory.newBuilder();
        dir.files.forEach((name, f) -> {
            d.addFiles(FileNode.newBuilder().setName(name).setDigest(f.digest()).setIsExecutable(f.executable()));
            blobs.putIfAbsent(f.digest(), () -> {
                try {
                    return Files.readAllBytes(f.path());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        });
        dir.dirs.forEach((name, child) -> d.addDirectories(DirectoryNode.newBuilder().setName(name).setDigest(build(child, blobs))));
        byte[] bytes = d.build().toByteArray();
        Digest digest = Cas.digest(bytes);
        blobs.putIfAbsent(digest, () -> bytes);
        return digest;
    }

    private void addFile(Dir input, Path root, Path file) {
        file = file.normalize();
        if (!Files.isRegularFile(file) || !file.startsWith(root)) {
            return;
        }
        Path relative = root.relativize(file);
        Dir dir = relative.getParent() == null ? input : input.dir(relative.getParent().toString().replace('\\', '/'));
        dir.files.put(relative.getFileName().toString(), entry(file));
    }

    private FileEntry entry(Path file) {
        try {
            long size = Files.size(file);
            long mtime = Files.getLastModifiedTime(file).toMillis();
            FileEntry known = fileMemo.get(file);
            if (known != null && known.size() == size && known.mtime() == mtime) {
                return known;
            }
            FileEntry e = new FileEntry(file, Cas.digest(file), Files.isExecutable(file), size, mtime);
            fileMemo.put(file, e);
            return e;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A file, or the files of a directory selected by Ant patterns. */
    private void addTree(Dir input, Path root, Path path, List<String> includes, List<String> excludes) throws IOException {
        path = path.normalize();
        if (Files.isRegularFile(path)) {
            addFile(input, root, path);
        } else if (Files.isDirectory(path)) {
            Path base = path;
            try (Stream<Path> files = Files.walk(path)) {
                for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    if (AntPattern.selected(base.relativize(f).toString().replace('\\', '/'), includes, excludes)) {
                        addFile(input, root, f);
                    }
                }
            }
        }
    }

    /** Everything in a module directory except build directories, VCS data and nested modules. */
    private void addModule(Dir input, Path root, Path moduleDir, Set<Path> moduleDirs) throws IOException {
        Path module = moduleDir.normalize();
        if (!module.startsWith(root)) {
            return;
        }
        Files.walkFileTree(module, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes a) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                boolean skip = !dir.equals(module) && (name.equals("target") || name.equals(".git")
                        || name.equals("node_modules") || moduleDirs.contains(dir));
                return skip ? java.nio.file.FileVisitResult.SKIP_SUBTREE : java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes a) {
                if (a.isRegularFile()) {
                    addFile(input, root, file);
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean inBuildDirectory(Path relative) {
        for (Path part : relative) {
            if (part.toString().equals("target")) {
                return true;
            }
        }
        return false;
    }

    private static Path resolve(String path, Path root, Path projectDir) {
        return Path.of(path.replace("{projectRoot}", projectDir.toString()).replace("{workspaceRoot}", root.toString()))
                .normalize();
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null) {
            array.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    /** Writes every project's final target directory into the workspace. */
    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i + pattern.length <= data.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private void download(Map<String, Task> tasks, List<TaskResult> results) throws IOException {
        Map<String, TaskResult> byId = new LinkedHashMap<>();
        results.forEach(r -> byId.put(r.id(), r));
        Map<String, TaskResult> latest = new LinkedHashMap<>();
        Map<String, String> paths = new LinkedHashMap<>();
        for (Task t : tasks.values()) {
            if (byId.get(t.id()).targetTree() != null) {
                latest.put(t.projectId(), byId.get(t.id()));
                paths.put(t.projectId(), t.projectPath());
            }
        }
        for (Map.Entry<String, TaskResult> e : latest.entrySet()) {
            Path target = options.workspace().resolve(paths.get(e.getKey())).resolve("target");
            deleteTree(target);
            byte[] tree = cas.read(e.getValue().targetTree());
            Map<Digest, Directory> directories = new LinkedHashMap<>();
            Directory root = null;
            CodedInputStream in = CodedInputStream.newInstance(tree);
            while (!in.isAtEnd()) {
                int tag = in.readTag();
                int field = WireFormat.getTagFieldNumber(tag);
                if (field == 1 || field == 2) {
                    byte[] raw = in.readByteArray();
                    Directory d = Directory.parseFrom(raw);
                    directories.put(Cas.digest(raw), d);
                    if (field == 1) {
                        root = d;
                    }
                } else {
                    in.skipField(tag);
                }
            }
            Map<Path, FileNode> files = new LinkedHashMap<>();
            collect(root, target, directories, files);
            Map<Digest, byte[]> contents = cas.readAll(files.values().stream().map(FileNode::getDigest).toList());
            byte[] token = SANDBOX_TOKEN.getBytes(StandardCharsets.UTF_8);
            for (Map.Entry<Path, FileNode> f : files.entrySet()) {
                Files.createDirectories(f.getKey().getParent());
                byte[] content = contents.get(f.getValue().getDigest());
                if (indexOf(content, token) >= 0 && indexOf(content, new byte[] {0}) < 0) {
                    // A text file naming the sandbox an action ran in: name this workspace instead.
                    content = new String(content, StandardCharsets.UTF_8)
                            .replace(SANDBOX_TOKEN, options.workspace().toString()).getBytes(StandardCharsets.UTF_8);
                }
                Files.write(f.getKey(), content);
                if (f.getValue().getIsExecutable()) {
                    f.getKey().toFile().setExecutable(true);
                }
            }
        }
    }

    private static void collect(Directory dir, Path at, Map<Digest, Directory> directories, Map<Path, FileNode> files)
            throws IOException {
        Files.createDirectories(at);
        for (FileNode f : dir.getFilesList()) {
            files.put(at.resolve(f.getName()), f);
        }
        for (DirectoryNode d : dir.getDirectoriesList()) {
            collect(directories.get(d.getDigest()), at.resolve(d.getName()), directories, files);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }

    private static Options parse(String[] args) {
        Path workspace = Path.of("").toAbsolutePath();
        String executor = "localhost:8980";
        String instance = "";
        String goal = "verify";
        String granularity = "stage";
        String maven = "mvn";
        String localMaven = "mvn";
        int parallel = 4;
        boolean download = false;
        Map<String, String> env = new LinkedHashMap<>();
        List<String> mavenArgs = new ArrayList<>();
        Path report = null;
        for (String a : args) {
            String value = a.contains("=") ? a.substring(a.indexOf('=') + 1) : "";
            switch (a.contains("=") ? a.substring(0, a.indexOf('=')) : a) {
                case "--workspace" -> workspace = Path.of(value).toAbsolutePath().normalize();
                case "--executor" -> executor = value;
                case "--instance" -> instance = value;
                case "--goal" -> goal = value;
                case "--granularity" -> granularity = value;
                case "--maven" -> maven = value;
                case "--local-maven" -> localMaven = value;
                case "--parallel" -> parallel = Integer.parseInt(value);
                case "--download" -> download = true;
                case "--env" -> env.put(value.substring(0, value.indexOf('=')), value.substring(value.indexOf('=') + 1));
                case "--maven-arg" -> mavenArgs.add(value);
                case "--report" -> report = Path.of(value).toAbsolutePath();
                default -> throw new IllegalArgumentException("unknown option " + a + "\n" + USAGE);
            }
        }
        return new Options(workspace, executor, instance, goal, granularity, maven, localMaven, parallel, download, env,
                mavenArgs, report);
    }

    private static final String USAGE = """
            Usage: ReapiBuild [options]
              --workspace=DIR       Maven workspace with the mojo-io extension (default: current directory)
              --executor=HOST:PORT  REAPI endpoint (default: localhost:8980)
              --instance=NAME       REAPI instance name (default: empty)
              --goal=PHASE          lifecycle goal (default: verify)
              --granularity=G       phase, stage (default) or module: one action per task of that size
              --maven=CMD           Maven command on the worker (default: mvn; mvnd keeps a JVM warm there)
              --local-maven=CMD     Maven command for the local graph export (default: mvn)
              --parallel=N          actions in flight (default: 4)
              --env=NAME=VALUE      environment of the actions (repeat; PATH and HOME are usually needed)
              --maven-arg=ARG       extra Maven argument for the graph export and every action (repeat)
              --download            write every project's final target directory into the workspace
              --report=FILE         write a JSON summary
            """;
}
