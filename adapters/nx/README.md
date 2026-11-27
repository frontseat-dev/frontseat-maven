# @mojo-io/nx

An Nx plugin for Maven builds, built entirely on mojo-io's tool-agnostic contract: the
[task graph](../../docs/task-graph.md), the [state files](../../docs/state-format.md) and single-task
invocations. It's an *adapter*: nothing in mojo-io knows about Nx.

## What it does

- **Projects and targets** come from a mojo-io graph export (a dry run, cached on the POMs, the
  plugin-io overrides and the JDK; about 0.2s through mvnd).
  - One Nx target per graph task. By default a task is a stage: `compile` (resources and compile),
    `test`, `package`, `verify`. See `granularity` below.
  - Every other lifecycle phase is a no-op target on the stage that covers it, so
    `nx run-many -t verify` (or any phase) works like `mvn <phase>`.
- **`dependsOn`** comes from Maven 4's phase links. For example `app:compile` and `app:test` depend on
  `lib-core:compile` ("ready"), not on `lib-core:test` or `lib-core:package`.
- **Running a target** runs exactly that task's mojos in one invocation, through mvnd when available
  (with `-Dmvnd.duplicateDaemonGracePeriod=3h`, so the daemons of parallel targets stay warm):
  `-pl <id>,<reactorReferences> -am -Dmojoio.only=<id> <groupId:artifactId:version:goal@execution>...`
  (reactor projects `-am` misses: named in configuration, or added while loading as Tycho does).
  - The extension replays the recorded state of everything else (artifact files, attached artifacts,
    generated source roots, JaCoCo's `argLine`).
  - So restored outputs are enough to continue.
- **Cache inputs:**
  - the task's source files and POMs;
  - the files build extensions read while loading the task's project and its reactor references
    (`loadFiles`: Tycho's manifests, `feature.xml`, the target definition);
  - the outputs of the tasks it depends on, transitively (`dependentTasksOutputFiles`), which covers the
    classpath: Maven's classpaths are transitive, so a change two modules up changes the hash;
  - a `-Dmojoio.fingerprint=` argument in the command, covering what Nx can't hash itself: evaluated
    plugin configuration, plugin versions, JDK and external dependencies.
- **Cache outputs:**
  - the declared outputs and the state files;
  - observed outputs (tests, agents): the build directory minus other tasks' outputs and bookkeeping.

## Limits

- **Targets run in the workspace, not in a fresh input root.** mojo-io expects each task to see only
  its declared inputs and the outputs of the tasks it depends on, and replays every state file it
  finds. In a workspace where a later target of an upstream project ran before (`package` left a jar
  and its state next to the classes), an earlier target can pick up that state. Run targets in Nx's
  sandbox where available, or start from a clean `target/` after changing an upstream project.
- **External dependencies count by coordinates.** A SNAPSHOT dependency from outside the workspace
  that was re-resolved under the same version doesn't change a target's hash.
- **The graph is cached** on the POMs, `.mvn/` (extensions, `maven.config`, `jvm.config`, `plugin-io/`),
  the user's `settings.xml`, the JDK and the plugin options. Environment variables or system
  properties that POMs read aren't part of that: after changing one, run `nx reset`.
- **File patterns of inputs aren't narrowed.** Where a mojo reads only some files of a directory, the
  target hashes the whole directory (too much is safe; Nx applies an exclusion to the whole target).
- **Aggregator mojos see only the target's reactor** (the project and what it depends on).

## Setup

1. Add the mojo-io extension to `.mvn/extensions.xml` (see the main README).
2. Add the plugin to `nx.json`:
   ```json
   { "plugins": ["@mojo-io/nx"] }
   ```

Options (`plugins[].options`):

| Option | Default | |
|---|---|---|
| `maven` | `$MOJOIO_MAVEN`, else `mvnd` if on `PATH`, else `mvn` | Maven executable for graph export and targets. Use mvnd: it saves JVM and Maven startup on every target. |
| `goal` | `verify` | Lifecycle goal the graph is computed for. |
| `granularity` | `stage` | Target size: `phase`, `stage` or `module`. Every target is one Maven invocation, so bigger targets make cold builds faster (20 modules cold: 32s, 20s, 17s; raw Maven 14s) and smaller ones cache more finely. |
| `args` | `[]` | Extra Maven arguments for every target. |

## Proof: `e2e/run.mjs`

```bash
mise run install                 # mojo-io into ~/.m2, for .mvn/extensions.xml
node adapters/nx/e2e/run.mjs     # from the repository root; MOJOIO_MAVEN=/path/to/mvnd
```

It uses Nx's self-hosted remote cache protocol (`e2e/cache-server.mjs`) as the shared cache between
checkouts, and checks:

1. **Cold build:** Nx runs every task. Classes, resources, jar contents and test results are identical
   to raw `mvn verify`.
2. **Fresh checkout, shared cache:** every task is restored and none runs. Outputs are identical to raw
   Maven.
3. **Continuing after a restore:** plain Maven continues on the restored checkout (app tests run with
   the JaCoCo agent, through replayed state).
4. **Changing a `lib-core` test:** only `lib-core`'s test tasks and what depends on their outputs re-run.
   `app`'s compile and test stay cached. Outputs are identical to raw Maven on the changed sources.
5. **Speed on a 20-module reactor:** raw mvn and mvnd against Nx cold at each granularity (outputs
   checked against raw Maven each time), Nx warm (fresh checkout with a shared cache), Nx after a
   change. Results are in [docs/performance.md](../../docs/performance.md).
