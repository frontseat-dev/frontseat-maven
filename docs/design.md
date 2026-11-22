# Design notes

## Goal

Nx-style task caching for Maven, at the granularity of a single mojo execution, without forking
Maven. Plugins stay unchanged: what a mojo reads and writes is declared in
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
components such as `LifecycleDependencyResolver` and `LifecycleExecutionPlanCalculator` can be
injected.

## Per execution

1. **Declaration lookup** by `groupId:artifactId:goal` (project → plugin jar → built-in), mapped
   to an executable descriptor. An unknown input/output kind makes the mojo non-cacheable. No descriptor: run. Aggregators: run, unless the declaration covers the
   whole reactor (a `reactorModels` input). A mojo that forks
   executions (`pmd:check` forks `pmd:pmd`) is one unit with them: their configuration and inputs join
   its key and their outputs join its entry. If one of them has no declaration, it runs.
2. **Resolve dependencies** for the mojo's `requiresDependencyResolution` scope *before* it runs, and
   set the project's artifact filter like `MojoExecutor` does. On a hit the runner is never called,
   but the key still needs the classpath.
3. **Key** = format version + plugin GAV + plugin dependencies + JDK vendor/version + effective
   configuration + descriptor inputs.
   - *Effective configuration*: every parameter in the finalized `Xpp3Dom`, evaluated the way Maven's
     configurator does it (expression, else `default-value`). Values are rendered deterministically,
     and absolute paths are tokenized (see below). A value we can't render (a Maven model object or a
     component) makes the execution non-cacheable unless the descriptor ignores that parameter. So
     the safe default is to run.
   - *Inputs*: content hashes of what the configuration points at: source trees, resource
     selections, classpath elements (by content, in order, excluding the mojo's own output
     directory).
4. **Hit**: copy the output blobs into the project and replay the state records. Before replaying,
   check that every local-repository file the state refers to (for example the JaCoCo agent jar) exists.
5. **Miss**: snapshot `ProjectState`, run, diff, collect the declared outputs, then store blobs first and
   the entry after them (atomic moves).

### Outputs are declared, not diffed

A mojo that finds its outputs up to date writes nothing. The compiler on an unchanged tree is one
example. A before/after file diff would then store an empty entry. Instead, descriptors declare
outputs by pattern (`outputDirectory/**/*.class`), or compute them (the files a resources execution
copies).

### Strict mode

Declarations can be wrong or incomplete, and an incomplete entry restores an incomplete build. So
every time a declared, cacheable mojo actually runs, the extension snapshots the module (size and
mtime of every file, skipping `.git`, nested reactor modules and its own `target/mojo-io`) before and
after. A file created, changed or deleted outside the declared outputs and the declaration's
`<ignoredWrites>` is an **undeclared write**:

- the execution is never stored;
- `mojoio.strict=warn` (default) logs the files, `fail` fails the build, `off` skips the check.

Running the check on the built-in declarations found exactly two kinds of bookkeeping (the compiler's
`maven-status/**`, the jar plugin's `maven-archiver/**`), plus one file Maven 4 itself writes during
whichever mojo runs first (`target/consumer-<n>.pom`), which is always exempt, like `.mvn/`. `StrictModeIT` runs a full `verify` with
`-Dmojoio.strict=fail`.

`mojoio.strict.cleanOutputs=true` additionally deletes declared outputs before such a run, so a
leftover from an earlier build (the class of a deleted source) cannot end up in the entry. It's
opt-in: some outputs are legitimately shared or appended to (several test runs writing one JaCoCo
`destfile`).

Limits: changes are detected by size and modification time, so a rewrite with identical size within
the filesystem's timestamp resolution goes unnoticed. Writes outside the module directory aren't
seen.

### Hooks

A *hook* is any way a mojo affects other mojos or the build outside its own declared outputs. Caching
one mojo at a time, or one task at a time in an external build system, only works if every hook is
carried across. There are five kinds, and each has one generic mechanism:

| Hook | Examples | Mechanism |
|---|---|---|
| **Process lifetime:** setup and teardown around a phase | `jetty:start`/`stop`, `exec:java` servers, docker start/stop | Maven 4 `before:`/`after:` hooks and Maven 3 `pre-`/`post-` phases are grouped with their phase as `runTogether`. A build system runs each group in one Maven invocation (`HookBracketsIT`, `Maven4HooksIT`). |
| **Project-property handoff** | `jacoco:prepare-agent` → `argLine`, buildnumber, port reservation | Recorded and replayed state. Surefire/failsafe late replacement (`@{argLine}`) is substituted in the key, so the key sees what the mojo will see. |
| **System-property handoff** | `properties:set-system-properties` | Recorded and replayed like project properties. JVM/runtime prefixes (`java.`, `os.`, `user.`, …) are ignored. |
| **Side outputs written inside another mojo's process** | Agents in the test JVM (JaCoCo `.exec`, profilers), test code writing files | `<observed/>` outputs: everything the mojo writes in the build directory, measured from the before/after snapshot, cached, and listed as `output` records in the state file for external caches. |
| **Model and lifecycle hooks of extensions** | flatten, CI-friendly versions, `@Execute` forks | Extensions run in every Maven process, so every task sees them. Mojos that fork a lifecycle are not cacheable. |

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
(`Maven4HooksIT.concurrentBuilderKeepsRecordedState`). Replay can also add an input path that doesn't
exist yet (the compiler's generated-sources root) before a key is computed. So paths without content
contribute nothing to keys, and the key doesn't depend on mojo order. Separately, in 4.0.0-rc-7 the concurrent builder
misorders Maven 3-style bindings even without mojo-io: it builds no jars and runs `jacoco:report` before
the tests. That's a Maven issue, not ours.

### Path normalization

`@@BASEDIR@@` (the module), `@@ROOT@@` (the multi-module root) and `@@M2REPO@@` (the local repository)
replace absolute paths in keys and stored state. The longest match wins, and matches must fall on path
boundaries. As a result:

- the same checkout at a different path hits (this is covered by the acceptance IT);
- modules with identical inputs share entries (`jacoco:prepare-agent` is one entry for every module);
- stored `argLine` values are relocated on restore.

### Project state

`ProjectState` captures compile and test source roots, resources, properties, the artifact file and
attached artifacts. Replayable diffs are: added source roots, property set/remove, the artifact
file, and newly attached artifacts. Removed source roots, changed resources and replaced attachments
make the execution non-cacheable
instead of being dropped.

**Main risk:** a mojo can change things the diff can't see: static fields, system properties, the
session. That is what descriptors are for. A mojo known to do this gets `notCacheable(reason)`.
We don't guess.

### Prerequisites for direct goals

If *every* execution in the list came from the CLI (`mvn surefire:test`), the strategy computes the
lifecycle plan up to the deepest `prerequisitePhase` of the requested goals and prepends it, minus
the goals already requested. With a warm cache those prerequisites are all restores. Mixed
invocations (`mvn test surefire:test`) are left alone. To turn this off, set
`-Dmojoio.prerequisites=false`.

## Cache layout

```
<dir>/cas/ab/<sha256>          file contents
<dir>/entries/12/<key>         text manifest: files (path, hash, size) + state records
<dir>/keys/12/<key>.txt        the key's inputs, to explain misses
```

## Things the ITs caught (keep these in mind for new descriptors)

- **`default-value` fallback.** `outputDirectory` is configured as `${maven.compiler.outputDirectory}`,
  which evaluates to null unless that property is set. Maven then uses the `default-value`. Without
  the fallback, outputs were collected from nowhere.
- **The artifact filter.** `LifecycleDependencyResolver` resolves the artifacts, but
  `getCompileClasspathElements()` stays empty until `MavenProject#setArtifactFilter` is called.
  Without it, classpath inputs silently covered only output directories, so a change in an upstream
  module didn't invalidate its dependents.
- **Self-referential classpaths.** `compilePath` starts with the mojo's own `target/classes`.
- **Unrelated properties.** Hashing *all* properties for filtered resources broke twice:
  - unrelated `-D` flags such as `-Dmaven.repo.local` changed the key;
  - on Maven 4, internal user properties appear between builds. Maven 4 writes
    `.mvn/target/project-local-repo` on the first build, which turns on root detection, so
    `session.rootDirectory` shows up on the second.

  Filtering now hashes exactly the expressions the filtered files reference.
- **Restoring `target/` isn't enough.** Without the recorded project state, a build continued from
  restored outputs can't resolve `app → lib-core` (the artifact file is lost) and loses JaCoCo's
  `argLine`. Hence the state files (ToolAgnosticIT).

## Known limitations of the first slice

- Stale outputs: if a source is deleted, a later miss can still store the stale `.class` the compiler
  left behind, unless `-Dmojoio.strict.cleanOutputs=true`.
- Toolchains are ignored. The key uses the JDK running Maven.
- A remote cache would also need plugin dependencies like the JaCoCo agent jar to exist locally.
  Today that case is a miss, not a failure.

## Roadmap

1. ~~**Strict mode**~~: done, see [Strict mode](#strict-mode).
2. **Remote cache**: an HTTP or REAPI-compatible CAS + action cache behind the same `LocalCache`
   API, with local as an L1 cache.
3. **Graph export**: emit the per-module execution graph with inputs and outputs for any orchestrator (Nx, Bazel, Frontseat, …) via a neutral, versioned JSON format
   (proposal P4).
4. **Upstreaming**: offer `plugin-io.xml` to the compiler, resources and surefire plugins, and
   discuss P1–P3 of the [proposal](maven4-proposal.md) with the Maven project.
5. **Declaration coverage**: the long tail of plugins (jar, source, javadoc, failsafe, protobuf,
   build-helper, …), ideally shipped by the plugins themselves.
6. **Edge cases**: forked executions, `@aggregator` mojos, plugins writing outside `target/`.
