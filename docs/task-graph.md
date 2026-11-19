# Task graph format (`mojo-io-task-graph`, version 1)

A tool-agnostic description of a Maven build as tasks, for any orchestrator or cache (Nx, Bazel,
Frontseat, CI systems, Maven itself). The schema is
[`task-graph-1.schema.json`](../mojo-io-spec/src/main/resources/dev/frontseat/mojoio/spec/task-graph-1.schema.json).

## Producing it

```bash
mvn verify -Dmojoio.graph.output=target/graph.json
```

This is a **dry run**: every mojo execution of the requested goals is described, and none runs.
Dependencies are not resolved either, so the export is fast and works on a fresh checkout. Ask for
the goals you want described; `verify` covers the default lifecycle up to packaging and tests.

## Content

```jsonc
{
  "format": "mojo-io-task-graph", "version": 1,
  "workspaceRoot": "/abs/path/of/checkout",      // what {workspaceRoot} stood for
  "maven": "3.9.11", "goals": ["verify"],
  "projects": [{
    "id": "com.example:app", "version": "1.0", "path": "app", "packaging": "jar",
    "dependsOn": ["com.example:parent", "com.example:lib-core"],   // reactor edges
    "poms": ["{projectRoot}/pom.xml", "{workspaceRoot}/pom.xml"],   // pom and in-workspace ancestors
    "executions": [{                                                // in execution order
      "id": "org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile@default-compile",
      "plugin": "org.apache.maven.plugins:maven-compiler-plugin", "version": "3.13.0",
      "goal": "compile", "executionId": "default-compile", "phase": "compile",
      "declaredBy": "built-in",            // or "plugin …", "project .mvn/plugin-io/x.xml", or null
      "cacheable": true, "reason": null,
      "inputs": [
        { "name": "compileSourceRoots", "kind": "files", "paths": ["{projectRoot}/src/main/java"], "includes": [], "excludes": [] },
        { "name": "compilePath", "kind": "classpath", "scope": "compile" }
      ],
      "outputs": [ { "name": "outputDirectory", "path": "{projectRoot}/target/classes", "includes": ["**/*.class"], "excludes": [] } ],
      "stateFile": "{projectRoot}/target/mojo-io/state/maven-compiler-plugin-compile-default-compile.state"
    }]
  }]
}
```

### Placeholders

- `{projectRoot}`: the project's directory.
- `{workspaceRoot}`: the multi-module root.
- `{localRepository}`: the local Maven repository.

Consumers substitute their own values.

### Inputs

| kind | Meaning for a consumer |
|---|---|
| `files` | Hash the files and trees in `paths`. Under a directory, only files selected by `includes`/`excludes` (Ant patterns; empty `includes` = everything). |
| `classpath` | The `scope` classpath (compile, test, runtime, …). It consists of the outputs of the `dependsOn` projects plus the external dependencies of that scope. Hash those, or depend on the upstream tasks. |
| `value` | A value as known at export time, e.g. a property the mojo reads. |

Besides the declared inputs, a conservative consumer also hashes `poms`: they hold the plugin
configuration, which the graph doesn't evaluate. It should also hash the JDK, which mojo-io's own
cache keys always include.

### Outputs and state

Restore `outputs` **and** `stateFile` together. The state file records what the execution changed in
the in-memory project; the extension replays it in later builds (see [state format](state-format.md)).
Without it, a restored `target/` can be unusable: for example, a downstream module can no longer
resolve its upstream module, and JaCoCo's agent isn't attached.

### Granularity

The graph is per mojo execution. Orchestrators that start one Maven process per task will usually
group the executions of one project and phase into one task:
- **Cacheable** only if every member execution is.
- **Inputs and outputs** are the union of its members', minus files produced by earlier members of
  the same group.

### Not cacheable

`cacheable: false` with a `reason` means one of:
- no declaration exists (see [plugin-io](plugin-io.md));
- the declaration says the mojo can't be cached;
- the mojo is an aggregator or forks a lifecycle;
- describing it failed.

A consumer should always run such executions.

### Limitations (v1)

- **Static analysis:** values reflect configuration before anything runs. A value an earlier mojo
  sets at runtime appears with its configured value. For example, the `destfile` of a JaCoCo agent
  that `prepare-agent` adds to surefire's `argLine` isn't listed as a surefire output.
- **Undeclared mojos** are listed but have no inputs or outputs.
