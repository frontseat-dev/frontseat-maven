# Design notes

## Goal

Let build systems (Nx, REAPI grids, Bazel-like tools) cache and sandbox Maven work at the granularity
of a mojo execution, without forking Maven. mojo-io describes the build as tasks and carries the
in-memory project state from one task to the next; the build system caches, sandboxes and checks
writes. Plugins stay unchanged: what a mojo reads and writes is declared in
[`plugin-io.xml`](plugin-io.md). Those declarations come from the project (`.mvn/plugin-io/`), from
the plugin's own jar, or from the extension's built-in file, in that order of precedence. The same
jar runs on Maven 3.9 and 4. [maven4-proposal.md](maven4-proposal.md) describes how this could
become part of Maven itself.

## Hook point

Maven 3.9 resolves `org.apache.maven.plugin.MojosExecutionStrategy` from the container and passes it
every project's list of mojo executions plus a `MojoExecutionRunner` that runs one of them. We register
`MojoIoExecutionStrategy` with `@Priority(10)`, so it wins over `DefaultMojosExecutionStrategy`.
`maven-build-cache-extension` uses the same mechanism. The extension is loaded as a core extension
(`.mvn/extensions.xml` or `-Dmaven.ext.class.path`). Its realm has maven-core as parent, so internal
components such as `LifecycleDependencyResolver` can be injected.

## Per invocation

- **Graph export** (`-Dmojoio.graph.output`): every execution is described and none runs. For each,
  the declaration is looked up by `groupId:artifactId:goal` (project → plugin jar → built-in) and
  mapped to an executable descriptor. An unknown input or output kind, an undeclared mojo, or an
  aggregator without a `reactorModels` input makes the execution not cacheable. A mojo that forks
  executions (`pmd:check` forks `pmd:pmd`) is one unit with them: their configuration and inputs join
  its inputs and fingerprint, their outputs join its outputs. No dependencies are resolved: classpaths
  are described by scope, and the external dependencies are listed per project.
- **Fingerprint** = format version + plugin GAV + plugin dependencies + JDK vendor/version + effective
  configuration + value inputs.
  - *Effective configuration*: every parameter in the finalized `Xpp3Dom`, evaluated the way Maven's
    configurator does it (expression, else `default-value`). Values are rendered deterministically,
    and absolute paths are tokenized (see below). A value we can't render (a Maven model object or a
    component) makes the execution not cacheable unless the descriptor ignores that parameter. So
    the safe default is to run.
  - *Artifacts named in configuration* (annotation processor paths) count by version; a SNAPSHOT or
    range makes the execution not cacheable. One built in this reactor is marked as such, and the
    graph makes the task wait for that project's tasks.
- **A task** (`-pl <id> -am -Dmojoio.only=<id> <invocations>`): projects not selected run nothing.
  The selected project first replays the recorded state of its upstream projects and of its own
  executions outside the invocation, then each execution runs. `ProjectState` is snapshotted around
  it, and the diff is written to the execution's state file.

### Outputs are declared, not diffed

A mojo that finds its outputs up to date writes nothing. The compiler on an unchanged tree is one
example. Descriptors therefore declare outputs by pattern (`outputDirectory/**/*.class`), or compute
them (the files a resources execution copies). Only mojos that run code with hooks of its own (tests,
agents) have `<observed/>` outputs: whatever they write in the build directory, which the build system
takes from the task's sandbox.

### What the build system owns

The extension trusts the files it finds: every state file in a task's input root is replayed. That
holds because the build system runs each task in a fresh input root with only the task's declared
inputs and the outputs of its `dependsOn`, checks the task's writes against its declared outputs and
caches the result. The recipe is in the
[task graph](task-graph.md#caching-maven-tasks-in-a-build-system).

### Hooks

A *hook* is any way a mojo affects other mojos or the build outside its own declared outputs. Caching
one mojo at a time, or one task at a time in an external build system, only works if every hook is
carried across. There are five kinds, and each has one generic mechanism:

| Hook | Examples | Mechanism |
|---|---|---|
| **Process lifetime:** setup and teardown around a phase | `jetty:start`/`stop`, `exec:java` servers, docker start/stop | Maven 4 `before:`/`after:` hooks and Maven 3 `pre-`/`post-` phases are grouped with their phase as `runTogether`. A build system runs each group in one Maven invocation (`HookBracketsIT`, `Maven4HooksIT`). |
| **Project-property handoff** | `jacoco:prepare-agent` → `argLine`, buildnumber, port reservation | Recorded and replayed state. Surefire/failsafe late replacement (`@{argLine}`) is substituted in the fingerprint, so it sees what the mojo will see. |
| **System-property handoff** | `properties:set-system-properties` | Recorded and replayed like project properties. JVM/runtime prefixes (`java.`, `os.`, `user.`, …) are ignored. |
| **Side outputs written inside another mojo's process** | Agents in the test JVM (JaCoCo `.exec`, profilers), test code writing files | `<observed/>` outputs: everything the mojo writes in the build directory, taken by the build system from the task's sandbox. |
| **Model and lifecycle hooks of extensions** | flatten, CI-friendly versions, `@Execute` forks | Extensions run in every Maven process, so every task sees them. Mojos that fork a lifecycle are not cacheable. Files an extension reads while projects load are the graph's `loadFiles`. |

#### Maven 4

In Maven 4, `before:`/`after:` hooks exist for every phase, and Maven 3's phases are aliases of them
(`process-resources` = `after:resources`, `process-classes` = `after:compile`, `prepare-package` =
`before:package`, `pre-integration-test` = `before:integration-test`). Executions report the hook name.
The graph therefore carries `canonicalPhase` (the Maven 3 name) and computes `runTogether` from the
Maven 4 form on both versions, so task names and groups are identical on Maven 3.9 and 4.

A hook is grouped with its phase only when both have executions bound (a lone `after:resources` is just
a task). That also keeps in-place post-processing together, such as an `after:compile` bytecode
enhancer that rewrites the compiler's classes.

The concurrent builder (`-b concurrent`) calls the execution strategy once per lifecycle step, with
partial plans. State is replayed before the first step, so an execution that runs later in the same
build already finds its own earlier changes in place, and its diff misses them. The strategy remembers
what it replayed and merges back the records that still hold after the execution runs
(`Maven4HooksIT.concurrentBuilderKeepsRecordedState`). Separately, in 4.0.0-rc-7 the concurrent builder
misorders Maven 3-style bindings even without mojo-io: it builds no jars and runs `jacoco:report` before
the tests. That's a Maven issue, not ours.

### Path normalization

`@@BASEDIR@@` (the module), `@@ROOT@@` (the multi-module root) and `@@M2REPO@@` (the local repository)
replace absolute paths in fingerprints, the graph (as `{projectRoot}`, `{workspaceRoot}`,
`{localRepository}`) and recorded state. The longest match wins, and matches must fall on path
boundaries. As a result:

- fingerprints are the same for the same checkout at another path, or in another sandbox;
- recorded `argLine` values and artifact files are relocated on replay.

### Project state

`ProjectState` captures compile and test source roots, resources, properties, the artifact file,
attached artifacts, project context values and (in sequential builds) system properties. Replayable
diffs are: added source roots, property set/remove, the artifact file, newly attached artifacts,
context values and system properties. Removed source roots, changed resources and replaced
attachments make the recorded state incomplete instead of being dropped.

**Main risk:** a mojo can change things the diff can't see: static fields, the session. That is what
declarations are for. A mojo known to do this is declared `cacheable="false"` with a reason. We don't
guess.

## Things the ITs caught (keep these in mind for new declarations)

- **`default-value` fallback.** `outputDirectory` is configured as `${maven.compiler.outputDirectory}`,
  which evaluates to null unless that property is set. Maven then uses the `default-value`. Without
  the fallback, outputs were described as nowhere.
- **Unrelated properties.** Putting *all* properties of filtered resources into the fingerprint broke
  twice:
  - unrelated `-D` flags such as `-Dmaven.repo.local` changed it;
  - on Maven 4, internal user properties appear between builds. Maven 4 writes
    `.mvn/target/project-local-repo` on the first build, which turns on root detection, so
    `session.rootDirectory` shows up on the second.

  Filtering now contributes exactly the expressions the filtered files reference.
- **Restoring `target/` isn't enough.** Without the recorded project state, a build continued from
  restored outputs can't resolve `app → lib-core` (the artifact file is lost) and loses JaCoCo's
  `argLine`. Hence the state files (`ToolAgnosticIT`).

## Known limitations

- Toolchains are ignored. The fingerprint uses the JDK running Maven.
- Replaying state that refers to a local-repository file (the JaCoCo agent jar) needs that file on the
  machine running the task. Without it, the state isn't replayed and the log says why.
- Values in the graph reflect configuration before anything runs (see the
  [task graph's limitations](task-graph.md#limitations-v1)).

## Roadmap

1. **Upstreaming**: offer `plugin-io.xml` to the compiler, resources and surefire plugins, and
   discuss P1–P3 of the [proposal](maven4-proposal.md) with the Maven project.
2. **Declaration coverage**: the long tail of plugins (javadoc, protobuf, …), ideally shipped by the
   plugins themselves.
3. **Edge cases**: plugins writing outside `target/`.
