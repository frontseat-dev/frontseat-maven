# mojo-io REAPI adapter

Builds a Maven workspace on a **Remote Execution API** service (Buildbarn, Buildfarm, BuildBuddy, …),
using only mojo-io's tool-agnostic contract: the [task graph](../../docs/task-graph.md), the
[state files](../../docs/state-format.md) and single-task invocations. It's an *adapter* outside the
mojo-io reactor: nothing in mojo-io knows about REAPI.

It's a reference client, not a build system. It shows that the contract is enough for a remote
execution grid, and what a Bazel rule set or another REAPI client has to do.

## How a task becomes an action

| REAPI | From the task graph |
|---|---|
| **Command** | `mvn -pl <id>,<reactorReferences> -am -Dmojoio.only=<id> <invocations>`, plus `-Dmojoio.fingerprint=` covering what isn't in the input root: evaluated plugin configuration, plugin versions, JDK, external dependencies. |
| **Input root** | `.mvn/`, every reactor POM, the task's declared `files` inputs, the `loadFiles` of its project and its `reactorReferences`, and the `target/` directory of each project among the tasks it depends on. |
| **Output** | The project's `target/` directory, which includes mojo-io's state files. |
| **Platform / tools** | Maven, the JDK and the local repository belong to the worker (its image). |

- **No bytes in between.** An upstream `target/` is mounted into the next action's input root by
  digest, straight from the action result. The client downloads outputs only at the end
  (`--download`).
- **The action key is REAPI's own** (command + input root), so a task with unchanged inputs is an
  action-cache hit from any checkout, at any path. Relocation works because mojo-io's state files hold
  no absolute paths.
- **A task with an undeclared mojo** gets its whole project directory as input and `do_not_cache`: the
  graph can't say what such a mojo reads or does, so it always runs.
- **Like Bazel, the client asks the action cache first** and only then calls `Execute`. Buildbarn
  leaves that lookup to the client.

Two things a REAPI client must do that a local build system doesn't:
- **Copy the project's own `target/` before running.** Inputs are immutable (workers hardlink them
  from a shared cache), but a later task of a project writes into the `target/` an earlier one
  produced. The action is wrapped in a small shell script that works on a copy.
- **Remove bookkeeping from the output.** The graph lists each execution's `ignoredWrites` (for example
  `maven-status/`, which holds absolute paths of the worker). The wrapper deletes them, so equal inputs
  give byte-identical outputs.
- **Relocate absolute paths.** Every action runs in its own sandbox directory, and some tools write
  absolute paths into `target/` (Tycho's `local-artifacts.properties` names the module's p2 repository
  files). After the build, the wrapper replaces the sandbox directory with a token in the text files of
  the output; before the build, it puts this sandbox's directory back in the mounted `target/`
  directories (each such file is replaced, not edited: inputs are hardlinks into the worker's cache).
  `--download` puts the local workspace there. Equal inputs thus also give byte-identical outputs when
  a tool records paths.

## Limits

- **Tool versions aren't in the action key.** The fingerprint covers the JDK of the machine that exports
  the graph, not the worker's JDK or Maven. Name the worker's toolchain in an `--env` value (for
  example `--env=TOOLCHAIN=jdk21-maven3.9.11`): the environment is part of the key.
- **External dependencies count by coordinates.** A SNAPSHOT dependency from outside the workspace
  that was re-resolved under the same version doesn't change a key.
- **Outputs live in `target/`.** A task that declares an input outside the workspace, or an output
  outside its project's `target/`, still runs but is reported and never cached: this client doesn't
  carry such files between actions.
- **Aggregator mojos see only the action's reactor** (the project and what it depends on), not the
  whole build.

## Usage

```bash
mise run install                                   # mojo-io into ~/.m2
mvn -q -f adapters/reapi/pom.xml package
java -cp "adapters/reapi/target/classes:adapters/reapi/target/lib/*" dev.frontseat.mojoio.reapi.ReapiBuild \
  --workspace=/path/to/project --executor=localhost:8980 --download \
  --env=PATH=/usr/bin:/bin --env=HOME=/home/build
```

The workspace needs the mojo-io extension in `.mvn/extensions.xml`. Options:

| Option | Default | |
|---|---|---|
| `--executor=HOST:PORT` | `localhost:8980` | REAPI endpoint (plain-text gRPC). |
| `--instance=NAME` | empty | REAPI instance name. |
| `--goal=PHASE` | `verify` | Lifecycle goal. |
| `--granularity=G` | `stage` | `phase`, `stage` or `module`: one action per task of that size. Each action starts Maven on a worker, so bigger is faster cold. |
| `--maven=CMD` | `mvn` | Maven command on the worker. |
| `--local-maven=CMD` | `mvn` | Maven command for the local graph export. |
| `--parallel=N` | 4 | Actions in flight. |
| `--env=NAME=VALUE` | none | Environment of the actions (part of the action key). |
| `--maven-arg=ARG` | none | Extra Maven argument for the graph export and every action. |
| `--download` | off | Write every project's final `target/` into the workspace. |
| `--report=FILE` | none | JSON summary. |

## Proof against Buildbarn: `e2e/Run.java`

```bash
adapters/reapi/e2e/buildbarn/build.sh    # once: Buildbarn from source, with Go
adapters/reapi/e2e/buildbarn/start.sh    # storage, scheduler, one worker; REAPI on localhost:8980
mise run e2e:reapi                       # MOJOIO_MVND=/path/to/mvnd to also measure the daemon
adapters/reapi/e2e/buildbarn/stop.sh
```

`e2e/buildbarn/config` is Buildbarn's own single-machine ("bare") configuration from
[bb-deployments](https://github.com/buildbarn/bb-deployments) (Apache-2.0), with smaller storage files
and 4 concurrent actions. The worker runs actions directly on the machine.

The script checks, against the running service:
1. **Cold:** every task executes on the worker. Downloaded classes, jar contents and test results
   are identical to raw `mvn verify`.
2. **Fresh checkout at another path:** every task is an action-cache hit and none executes. Outputs
   are identical to raw Maven.
3. **Changing a `lib-core` test:** `lib-core:compile`, `app:compile` and `app:test` stay cached.
   Outputs are identical to raw Maven on the changed sources.
4. **Speed on a 20-module reactor:** cold per stage and per module, with `mvn` and with `mvnd` on the
   worker; a fresh checkout; a change. Results are in [docs/performance.md](../../docs/performance.md).

The proto files under `src/main/proto` are copies from
[bazelbuild/remote-apis](https://github.com/bazelbuild/remote-apis) and
[googleapis](https://github.com/googleapis/googleapis) (both Apache-2.0).
