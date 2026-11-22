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
    "externalDependencies": ["org.junit.jupiter:junit-jupiter-api:jar:5.11.4:test", "…"],
    "executions": [{                                                // in execution order
      "id": "org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile@default-compile",
      "plugin": "org.apache.maven.plugins:maven-compiler-plugin", "version": "3.13.0",
      "goal": "compile", "executionId": "default-compile", "phase": "compile",
      "invocation": "org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile@default-compile",
      "canonicalPhase": "compile",         // Maven 3 name (Maven 4 reports e.g. "after:resources" = "process-resources")
      "runTogether": null,                 // or e.g. "integration-test" for a phase and its hooks
      "fingerprint": "9f2c…",              // config + plugin + JDK + values; null if not cacheable
      "declaredBy": "built-in",            // or "plugin …", "project .mvn/plugin-io/x.xml", or null
      "stamping": "carry",                 // "exact": the version is an input; "carry": only copied (docs/versioning.md)
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

## Caching Maven tasks in a build system

What a build system (Nx, Bazel, Turborepo, a CI cache, …) needs, all in the graph:

1. **Tasks.** Use each project's ready-made `tasks`. Each one groups executions by the chosen
   [granularity](#granularity) (by default a stage: compile, test, package, verify) and lists its
   `dependsOn` task ids (see [Phase links](#phase-links) below). To build
   tasks yourself, group by `canonicalPhase`, which is the same on Maven 3 and 4. Executions sharing a `runTogether` value (a phase
   with its `pre-`/`post-` or Maven 4 `before:`/`after:` hooks) **must** be one task. Hooks hold
   process-local state: a server started in `pre-integration-test` dies with that Maven process.
2. **Task hash.** Combine:
   - the members' `fingerprint`s, which cover what the build system can't hash itself (evaluated
     plugin configuration, plugin version and dependencies, JDK, value inputs);
   - the `files` inputs;
   - `poms` and `externalDependencies`;
   - the hashes of the tasks in the task's `dependsOn`, which also cover the classpath.
   In Nx, put the fingerprints into the task's options, which Nx hashes.
3. **Cache.** Store each member's `outputs` and its `stateFile`. An output with `"observed": true` (tests,
   agents) is known only after the run: store the files listed as `output` records in the state file.
   On a hit, copy everything back.
4. **Run one task.** Run
   `mvn -pl <id> -am -Dmojoio.only=<id> -Dmojoio.prerequisites=false -Dmojoio.cache.enabled=false <invocation>...`.
   - `-am` puts the upstream projects in the reactor, so their restored outputs resolve.
   - `mojoio.only` makes sure only this project's mojos run. If the build ends without failing and
     without building a selected project, the extension fails it: an empty result must never be
     cached as a success. The other projects' state is replayed
     only as far as the task's `dependsOn` reach, so leftovers of later tasks in the workspace (an
     old jar) are never picked up.
   - The extension replays the recorded state of everything that doesn't run: artifact files,
     attachments, generated source roots, properties like JaCoCo's `argLine`.

Fingerprints include stamp values such as the project version, because a build system restores outputs
byte for byte and can't rewrite the version inside them.

`ExternalBuildSystemIT` does exactly this, with a small reference implementation
(`ReferenceBuildSystem`) and mojo-io's own cache switched off, at `phase` and at `stage` granularity. A fresh checkout is restored entirely from
the build system's cache, and editing one test re-runs exactly that project's `test-compile` and `test` (or its `test` stage).
`HookBracketsIT` shows that splitting a hook bracket breaks the integration tests.

### Phase links

Maven 4 defines the lifecycle as a tree of phases with **links**. A phase waits for phases of the same
project, of its dependencies (in a scope) or of its children. The graph's `lifecycle` section carries
that model. It's read from Maven 4 at run time; on Maven 3 a built-in copy of the Maven 4 default is
used, so the graph is the same on both versions.

The default links, from 4.0.0-rc-7:

| Phase | Waits for |
|---|---|
| `compile` | own `sources`; **dependencies' (compile scope) `ready`** |
| `ready` | own `compile` and `resources` |
| `package` | own `ready`; **dependencies' (runtime scope) `package`** |
| `test-compile` | own `test-sources` and `ready`; dependencies' (test-only) `ready` |
| `test` | own `test-compile` and `test-resources`; **dependencies' (test scope) `ready`** |
| `install`, `deploy` | own `package` |

Each task's `dependsOn` has three parts:
- the previous task of its project;
- for every dependencies-link of its `lifecyclePhases`, the last task of each matching reactor dependency
  with work the linked phase covers;
- for every scope one of its mojos makes Maven resolve (`requiresDependencyResolution` on the
  execution), the "ready" task of each reactor dependency in that scope. The links alone miss this: a
  check bound to `validate` that resolves the test classpath needs the project's test-scope
  dependencies built, although no link of the compile stage asks for them.

Where a task runs code (the test and runtime scopes), it also waits for the compile- and runtime-scoped
reactor dependencies of its dependencies, transitively. A project's own compile task doesn't wait for
its runtime dependencies, so they wouldn't be reached through it.

"Ready" (classes compiled) only serves a dependency on the plain main artifact. A dependency on
anything else needs the upstream project's **package** task instead, because the file only exists once
it's packaged:
- an attached artifact: any classifier (`tests`, `sources`, `javadoc`, …) or a type that implies one
  (`test-jar`, `java-source`, `javadoc`, `ejb-client`);
- an archive such as `war` or `zip`.

Each entry of a project's `dependencies` carries `scope`, `type` and `classifier` for this.

So `app:compile` and `app:test` depend on `lib-core:compile` ("ready"), not on `lib-core:test` or
`lib-core:package`. In `ExternalBuildSystemIT` (at `phase` granularity), changing a `lib-core` test re-runs exactly
`lib-core:test-compile` and `lib-core:test`; every `app` task stays a hit, and with project-level
edges they would all have re-run.

Note: in rc-7, `integration-test` has no link to `package`, and its concurrent builder misorders
integration tests accordingly. Within a project, tasks stay chained in plan order, which keeps the
Maven 3 order.

### Granularity

`executions` are always listed one by one. `mojoio.graph.granularity` sets how `tasks` group them
(the graph's `granularity` field records it):

| Value | Tasks per project | Use when |
|---|---|---|
| `phase` | One per lifecycle phase with its hooks (`runTogether`), e.g. `process-resources`, `compile`, `test-compile`, `test`, `package`. | You want the finest caching and invocations are cheap. |
| `stage` (default) | `compile` (everything up to Maven 4's `ready`: sources, resources, compile), `test` (unit tests), `package`, `verify` (integration tests and verification), `install`, `deploy`. | Usual choice: about half the invocations of `phase`. |
| `module` | One: `build`. | Invocations are expensive (no daemon, remote execution), or you only want module-level caching. |

Every task is one Maven invocation, which costs about 0.1s through mvnd and seconds without a daemon
(see [performance](performance.md#what-a-maven-invocation-costs-and-how-to-pay-it-less-often)).
Bigger tasks mean fewer invocations; smaller tasks mean a change invalidates less.

Phase links hold at every granularity. With `stage`, `app:compile` and `app:test` depend on
`lib-core:compile` only, so changing a `lib-core` test re-runs `lib-core:test` and nothing in `app`
before packaging. With `module`, `app:build` depends on `lib-core:build`.

For a task of several executions:
- **Cacheable** only if every member execution is.
- **Inputs and outputs** are the union of its members', minus files produced by earlier members of
  the same task. (Those don't exist yet when the task is hashed on a fresh checkout.)
- A `runTogether` group is never split: stages are made of whole phases with their hooks.

### Aggregators

An execution with `"aggregator": true` is an aggregator mojo. Invoked as a goal (which is how a task
runs), Maven runs an aggregator **once, for the build's top-level project**, whatever project it is
bound in. So in a task of another project it doesn't run at all, and in the top-level project's task
"the build" is that project and what it depends on, not the whole workspace. Where the aggregate over
the whole build matters (an aggregate BOM, aggregated reports), run that goal in a full build.

### Plugins built in the build

`pluginReactorDependencies` lists the reactor projects a mojo's plugin is made of: the plugin itself
or its `<dependencies>` (for example a build-tools module holding a checkstyle configuration). The task
depends on their package tasks.

### Not cacheable

`cacheable: false` with a `reason` means one of:
- no declaration exists (see [plugin-io](plugin-io.md));
- the declaration says the mojo can't be cached;
- the mojo is an aggregator, or forks an execution that isn't declared (a mojo that forks declared
  goals is one execution in the graph, with their inputs and outputs);
- describing it failed.

A consumer should always run such executions.

### Limitations (v1)

- **Static analysis:** values reflect configuration before anything runs. A value an earlier mojo
  sets at runtime appears with its configured value. For example, the `destfile` of a JaCoCo agent
  that `prepare-agent` adds to surefire's `argLine` isn't listed as a surefire output.
- **Undeclared mojos** are listed but have no inputs or outputs.
