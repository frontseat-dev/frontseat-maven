package dev.frontseat.mojoio.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/** The task graph a build exports, and lookups into it for assertions. */
final class TaskGraph {

    private TaskGraph() {}

    /** Exports the graph of {@code goals} (plus {@code -D} flags) in {@code build} and reads it. */
    static JsonNode export(MavenBuild build, String... goals) {
        List<String> args = new ArrayList<>(List.of(goals));
        args.add("-Dmojoio.graph.output=graph.json");
        build.run(args.toArray(String[]::new));
        try {
            return new ObjectMapper().readTree(build.dir().resolve("graph.json").toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonNode project(JsonNode graph, String id) {
        for (JsonNode p : graph.get("projects")) {
            if (p.get("id").asText().equals(id) || p.get("id").asText().endsWith(":" + id)) {
                return p;
            }
        }
        throw new AssertionError("no project " + id);
    }

    /** The project's first execution of {@code goal}. */
    static JsonNode execution(JsonNode project, String goal) {
        for (JsonNode e : project.get("executions")) {
            if (e.get("goal").asText().equals(goal)) {
                return e;
            }
        }
        throw new AssertionError("no execution " + goal);
    }

    static JsonNode task(JsonNode project, String name) {
        for (JsonNode t : project.get("tasks")) {
            if (t.get("name").asText().equals(name)) {
                return t;
            }
        }
        throw new AssertionError("no task " + name);
    }

    static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
