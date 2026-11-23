# mojo-io

**Per-mojo inputs, outputs and project state for Maven: tool-agnostic.**

mojo-io makes every Maven mojo execution a well-defined task: what it reads, what it writes, and
what it changes in the in-memory project. It is neutral infrastructure. A build cache, a monorepo
orchestrator (Nx, Bazel, Frontseat, …) or Maven itself can consume it; mojo-io knows about none of
them. Tool integrations live outside it, as adapters.

## Architecture

| Layer | What | Knows about tools? |
|---|---|---|
| **Specs** (`mojo-io-spec`) | Versioned, documented formats, JDK-only with no Maven dependency: [`plugin-io.xml`](docs/plugin-io.md) declares mojo I/O, the [task graph](docs/task-graph.md) describes a build as tasks (JSON Schema), and the [project-state file](docs/state-format.md) records what each execution changed in the in-memory project. | No |
| **Maven extension** (`mojo-io-maven-extension`) | An ordinary core extension (`.mvn/extensions.xml`) for Maven 3.9 and 4. Three independent features: **graph export** (a dry run), **state record/replay** (so a restored `target/` is enough to continue a build), and an optional **local cache**. | No |
| **Adapters** (outside this repo's core) | Nx, Frontseat, Bazel, CI caches, … Each translates the neutral formats for one tool. | Yes, one tool each |

You don't need a Maven fork or a core patch, and you don't have to change your POMs.

## Performance

On a 20-module reactor: a cold build costs about **6%** more than raw Maven. A warm cache makes a clean
build **5.8× faster** with mvn and **20× faster** with mvnd (0.6s). One build-system task takes 0.6s
through mvnd. Details in [docs/performance.md](docs/performance.md).

On a real project (Apache Commons Numbers, 14 modules), a warm `clean verify` takes 5s against 132s
for plain Maven, with identical outputs: [docs/real-projects.md](docs/real-projects.md).

## For tool authors

mojo-io is meant to be cached **by your build system** (Nx, Bazel, CI caches, …), not only by its
built-in cache. To integrate it with an orchestrator or cache (the full recipe is in
[task graph: caching Maven tasks](docs/task-graph.md#caching-maven-tasks-in-a-build-system)):
1. **Read the graph.** Run `mvn verify -Dmojoio.graph.output=graph.json`. This is a dry run that
   describes every mojo execution without running any. The graph gives each execution's inputs,
   outputs, project dependencies and cacheability ([format](docs/task-graph.md)).
2. **Schedule and cache.** Cache each task's `outputs` **and its `stateFile`**.
3. **Restore and continue.** Restore those files and run plain `mvn` with the extension loaded. It
   replays the recorded project state (artifact files, generated source roots, properties like
   JaCoCo's `argLine`) for executions that don't run, relocated to the current checkout. Pass
   `-Dmojoio.prerequisites=false -Dmojoio.cache.enabled=false` if your tool does the scheduling and
   caching itself.

## The built-in cache (one consumer)

Every mojo execution is wrapped by a custom `MojosExecutionStrategy`. For goals it has a descriptor
for, mojo-io computes a cache key from the plugin, the JDK, the effective configuration and the
content of the declared inputs. On a hit it restores the output files *and* the changes the mojo made to
the `MavenProject` (source roots, properties like `argLine`, the artifact file), then skips the
mojo.

```
[mojo-io] HIT   jacoco:prepare-agent (default) @ lib-core [9b7380f2feb5] restored 0 file(s), 1 state change(s)
[mojo-io] HIT   resources:resources (default-resources) @ lib-core [b26d37d233dd] restored 1 file(s), 0 state change(s)
[mojo-io] HIT   compiler:compile (default-compile) @ lib-core [67d916192e10] restored 1 file(s), 2 state change(s)
[mojo-io] HIT   compiler:testCompile (default-testCompile) @ lib-core [134d1b66a470] restored 1 file(s), 1 state change(s)
[mojo-io] MISS  surefire:test (default-cli) @ lib-core [60ae2c0f3daa] stored 3 file(s), 0 state change(s)
```

mojo-io works on **Maven 3.9 and Maven 4** from the same jar. What each mojo reads and writes is
declared in [`plugin-io.xml`](docs/plugin-io.md), a small format with no Maven dependency.
Plugins can ship it, projects can override it, and Maven 4 could adopt it; see the
[proposal](docs/maven4-proposal.md).

## Status

First slice, working end to end on Maven 3.9.11 and 4.0.0-rc-7. Which plugins are covered, and how
well, is tracked in the [compatibility registry](#plugin-compatibility-registry) below.

Mojos nobody declares always run normally. If anything unexpected happens while computing a key or
storing an entry, the mojo just runs.

## Plugin compatibility registry

**Statuses:**
- ✅ **verified**: declared, and exercised end to end by the integration tests on both Maven lines.
- 🟡 **declared**: a declaration exists and every parameter it references exists in the listed
  versions (checked against each version's `plugin.xml`), but there's no end-to-end IT for it.
- 📄 **example declaration**: not built in; copy the file into `.mvn/plugin-io/`. Verified on a real
  project ([docs/real-projects.md](docs/real-projects.md)).
- ⛔ **not cacheable**: has side effects outside the project, so it always runs, by design.
- ➖ **not yet**: no declaration, so it always runs.

| Plugin | Goal | Status | IT-verified version | Parameters checked in | Restores | Notes |
|---|---|---|---|---|---|---|
| `maven-resources-plugin` | `resources`, `testResources` | ✅ | 3.3.1 | 3.1.0, 3.2.0, 3.3.0, 3.3.1, 4.0.0-beta-1 | copied files | `carry`. Filtering hashes exactly the `${…}`/`@…@` expressions the files reference. `${maven.build.timestamp}` in a filtered file means it misses every time. |
| `maven-compiler-plugin` | `compile`, `testCompile` | ✅ | 3.13.0 | 3.8.1 – 3.14.0 | classes, generated sources, source root, artifact file | `carry`. **4.x is not compatible** (no `compilePath`/`testPath`) and runs uncached. Annotation processors in `annotationProcessorPaths` count by coordinates (a version from dependency management included); a SNAPSHOT or range processor version runs uncached. Toolchains are not in the key. |
| `maven-surefire-plugin` | `test` | ✅ | 3.5.2 | 2.22.2, 3.0.0, 3.2.5, 3.5.2, 3.5.4 | reports, JaCoCo `destfile` | `exact`. Tests that use the network, a DB, env vars or files outside the project get cached too: override with `.mvn/plugin-io/`. With `testFailureIgnore`, failures are cached. |
| `maven-failsafe-plugin` | `integration-test`, `verify` | ✅ | 3.5.2 | 2.22.2, 3.0.0, 3.2.5, 3.5.2, 3.5.4 | reports, summary | `exact`. Integration tests often reach outside the project; mark them `cacheable="false"` in `.mvn/plugin-io/` if yours do. |
| `maven-jar-plugin` | `jar`, `test-jar` | ✅ | 3.4.2 | 3.2.2, 3.3.0, 3.4.1, 3.4.2 | the jar, artifact file / attached artifact | `carry`: after a version bump the restored jar is renamed and its `pom.properties`, pom and manifest are restamped. |
| `maven-source-plugin` | `test-jar-no-fork` | ✅ | 3.3.1 | 3.2.1, 3.3.0, 3.3.1 | test sources jar, attached artifact | `carry`. |
| `maven-resources-plugin` | `copy-resources` | 🟡 | | 3.1.0 – 3.3.1 | copied files | `carry`. Resources written in the plugin configuration are supported. |
| `build-helper-maven-plugin` | `parse-version`, `regex-property`, `regex-properties` | ✅ (`parse-version`) | 3.6.1 | 3.6.0, 3.6.1 | project properties | Only compute properties from their configuration. |
| `maven-source-plugin` | `jar-no-fork` | ✅ | 3.3.1 | 3.2.1, 3.3.0, 3.3.1 | sources jar, attached artifact | `carry`. The forking `jar` goal is not cacheable (it forks a lifecycle with undeclared mojos); use `jar-no-fork`. |
| `jacoco-maven-plugin` | `prepare-agent` | ✅ | 0.8.12 | 0.8.7, 0.8.10, 0.8.12, 0.8.13, 0.8.14 | `argLine` | A hit requires the agent jar in the local repository; otherwise it counts as a miss. |
| `jacoco-maven-plugin` | `check` | ✅ | 0.8.12 | 0.8.7, 0.8.10, 0.8.12, 0.8.13, 0.8.14 | nothing (fails the build when rules aren't met) | Hits while the coverage data and classes are unchanged. |
| `jacoco-maven-plugin` | `report` | ✅ | 0.8.12 | 0.8.7, 0.8.10, 0.8.12, 0.8.13, 0.8.14 | `site/jacoco` | `exact`. Hits whenever the coverage data is unchanged (e.g. tests restored from the cache). |
| `maven-checkstyle-plugin` | `check` | 📄 | | 3.6.0 | `checkstyle-result.xml` | Copy [`examples/plugin-io/static-analysis.xml`](examples/plugin-io/static-analysis.xml). Covers the configuration files the plugin points at directly; read the file's notes first. |
| `spotbugs-maven-plugin` | `check` (forks `spotbugs`) | 📄 | | 4.10.4.0 | SpotBugs reports | Same file. Cached as one unit with the forked `spotbugs` goal. |
| `maven-pmd-plugin` | `check` (forks `pmd`) | 📄 | 3.28.0 (`ForkedGoalsIT`) | 3.28.0 | `pmd.xml` | Same file. Cached as one unit with the forked `pmd` goal. |
| `maven-enforcer-plugin` | `enforce` | 📄 | | 3.6.3 | nothing | Copy [`examples/plugin-io/common-plugins.xml`](examples/plugin-io/common-plugins.xml). Covers rules on the project, the Maven version and the JDK; not rules that read the environment or files. |
| `apache-rat-plugin` | `check` | 📄 | | 0.18 | `rat.txt` | Same file. Input: all of the project's own files. |
| `maven-artifact-plugin` | `check-buildplan` | 📄 | | | nothing | Same file. |
| `maven-remote-resources-plugin` | `process` | 📄 | | 3.3.0 | generated resources | Same file. An execution that adds a resource directory to the project runs uncached. |
| `maven-bundle-plugin` | `manifest` | 📄 | | | the OSGi manifest | Same file. |
| `maven-site-plugin` | `attach-descriptor` | 📄 | | 3.22.0 | site descriptor, attached artifact | Same file. |
| `maven-changes-plugin` | `changes-validate` | 📄 | | 3.0.0-M3 | nothing | Same file. |
| `japicmp-maven-plugin` | `cmp` | 📄 | | 0.26.2 | `japicmp/` reports | Same file. Input: the built jar; the old version comes from the configuration. |
| `cyclonedx-maven-plugin` | `makeAggregateBom` | 📄 | | 2.9.3 | the aggregate BOM | Same file. An aggregator: cached because the declaration covers the whole reactor (`reactorModels`). Needs network access. |
| `spdx-maven-plugin` | `createSPDX` | 📄 | | | the SPDX document | Same file. |
| `moditect-maven-plugin` | `add-module-info` | 📄 | | | the rewritten jar | Same file. |
| `tycho-compiler-plugin` | `compile`, `testCompile` | 📄 | | 5.0.1 | classes | Copy [`examples/plugin-io/tycho.xml`](examples/plugin-io/tycho.xml). p2 bundles count by their qualified versions. Use a fixed or git-based build qualifier. |
| `spring-boot-maven-plugin` | `build-info`, `repackage` | 📄 | | 4.1.0 | `build-info.properties`, the executable jar | Copy [`examples/plugin-io/spring-boot.xml`](examples/plugin-io/spring-boot.xml). Set `project.build.outputTimestamp` for `build-info`. |
| `spring-javaformat-maven-plugin` | `validate` | 📄 | | 0.0.47 | nothing | Same file. |
| `maven-antrun-plugin` | `run` | 📄 | | | whatever it writes | An Ant script can do anything: declare it per project, for one known script ([example](examples/plugin-io/apache-commons-parent.xml)). |
| `maven-javadoc-plugin` | `javadoc`, `jar` | ➖ | | | | |
| `maven-clean-plugin` | `clean` | ⛔ | | | | Deletes outputs; nothing to cache. |
| `maven-install-plugin` | `install` | ⛔ | | | | Writes to the local repository; works from replayed state (artifact file + attachments). |
| `maven-deploy-plugin` | `deploy` | ⛔ | | | | Publishes to a remote repository. |

`mise run registry:check` re-verifies the "Parameters checked in" column: it downloads each version
listed in `registry/checked-versions.txt` and checks that every parameter a declaration references
exists on that goal. At runtime, a declaration that references a parameter the mojo doesn't have
(i.e. another plugin version) makes the execution run uncached, and the log line explains why. A plugin's own `META-INF/maven/plugin-io.xml` and a project's `.mvn/plugin-io/` override
the built-in declarations. To add a row, add the declaration to `builtin-plugin-io.xml`, list the
versions in `registry/checked-versions.txt`, run `mise run registry:check`, and add an IT before
marking it ✅.

**Tip:** set `project.build.outputTimestamp` (reproducible builds) in your projects. mojo-io
doesn't need it, but it makes the jars themselves identical across builds, which a shared or remote
cache will benefit from. Maven 4 sets a default.

## Tooling: mise

[mise](https://mise.jdx.dev) pins the JDK and both Maven lines, and defines the tasks. There is no
Maven wrapper.

```bash
mise install            # temurin-21, Maven 3.9.11 and 4.0.0-rc-7
mise tasks              # list tasks
```

| Task | |
|---|---|
| `mise run build` | Compile and package every module, no tests. |
| `mise run test` | Unit tests. |
| `mise run it` | Unit + integration tests; the ITs fork Maven 3.9. |
| `mise run it:maven4` | Integration tests forking Maven 4 (asserts the forked version). |
| `mise run ci` | `it` then `it:maven4`. This is what GitHub Actions runs. |
| `mise run demo -- [--maven4] [--fresh] <goals>` | Run the sample project with the extension, e.g. `mise run demo -- --fresh surefire:test`. |
| `mise run install` | Install to `~/.m2` for use via `.mvn/extensions.xml`. |
| `mise run cache:clear` | Delete the local cache. |
| `mise run it:mvnd` | Integration tests through the Maven Daemon (`MOJOIO_MVND_HOME` overrides the mise-installed one). |
| `mise run perf` | Benchmark raw Maven vs mojo-io, with mvn and mvnd ([results](docs/performance.md)). |
| `mise run registry:check` | Verify the built-in declarations against every plugin version in `registry/checked-versions.txt`. |

## Use it in a project

After `mise run install` (or with a [published version](#published-versions)), add `.mvn/extensions.xml` to any Maven 3.9+ or 4 project:

```xml
<extensions>
  <extension>
    <groupId>dev.frontseat.mojoio</groupId>
    <artifactId>mojo-io-maven-extension</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </extension>
</extensions>
```

Goals invoked directly get their prerequisites. On a fresh checkout, `mvn surefire:test` first runs
or restores the lifecycle up to `process-test-classes`, then runs the tests.

To declare or override a mojo's I/O for one project, for example to stop caching tests that hit a
live service, drop a file in `.mvn/plugin-io/`:

```xml
<plugin-io xmlns="urn:mojo-io:plugin-io:1.0">
  <plugin groupId="org.apache.maven.plugins" artifactId="maven-surefire-plugin">
    <mojo goal="test" cacheable="false" reason="integration tests hit a live service"/>
  </plugin>
</plugin-io>
```

### Settings

| Property | Default | |
|---|---|---|
| `mojoio.enabled` | `true` | Master switch. |
| `mojoio.state.enabled` | `true` | Record project-state files in `target/mojo-io/state/` and replay them for executions that don't run. |
| `mojoio.graph.output` | unset | Write the task graph to this file instead of running mojos (dry run). |
| `mojoio.graph.granularity` | `stage` | How the graph groups a project's executions into tasks: `phase`, `stage` (compile, test, package, verify) or `module`. Fewer, bigger tasks mean fewer Maven invocations for a build system ([task graph](docs/task-graph.md#granularity)). |
| `mojoio.cache.enabled` | `true` | The built-in local cache. |
| `mojoio.stamp.properties` | unset | Extra stamp values besides `project.version`, e.g. `buildNumber,git.commit.id` ([versioning](docs/versioning.md)). |
| `mojoio.cache.dir` | `~/.m2/mojo-io-cache` | Local cache location. |
| `mojoio.cache.read` / `mojoio.cache.write` | `true` | E.g. `write=false` for a read-only CI consumer. |
| `mojoio.strict` | `warn` | Strict mode: report files a declared mojo writes outside its declared outputs. Such executions are never cached; `fail` fails the build (use it in CI to enforce declarations); `off` disables the check. |
| `mojoio.strict.cleanOutputs` | `false` | Delete declared outputs before a declared mojo runs, so leftovers (e.g. classes of deleted sources) never reach a cache entry. Opt-in, because some outputs are shared or appended to (JaCoCo's `destfile`). |
| `mojoio.only` | unset | Comma-separated project ids. Other reactor projects run no mojos; their recorded state is replayed as far as the selected project's goals need it ([rules](docs/state-format.md#replay-rules)): `-pl app -am -Dmojoio.only=app <invocation>` runs one task of `app` against its upstream modules' restored outputs. |
| `mojoio.only.inputCheck` | `fail` | With `mojoio.only`: fail a task whose declared mojo reads files outside the build directories that the dry-run task graph didn't list, such as a source root an earlier mojo added. `warn` logs instead, `off` skips the check. See [what a dry run can't see](docs/task-graph.md#what-a-dry-run-cant-see). |
| `mojoio.prerequisites` | `true` | Expand directly-invoked goals with their lifecycle prerequisites. |

### Why did it miss?

Every cacheable execution writes its key's inputs, and which declaration was used, to
`target/mojo-io/<plugin>-<goal>-<executionId>.key.txt`. The cache keeps a copy per key under
`<cache>/keys/`. To see which input changed, diff two of them.

## Published versions

Releases go to **GitHub Packages** (`.github/workflows/release.yml`), versioned from
[Conventional Commits](https://www.conventionalcommits.org):

- **A release:** run the *Release* workflow (Actions > Release > Run workflow). With no version given,
  the version comes from the commits since the last `v*` tag: `feat:` makes a minor release, `!` or
  `BREAKING CHANGE:` a major one, anything else a patch release. The maven-release-plugin computes it
  with the [conventional-commits version policy](https://github.com/nielsbasjes/conventional-commits-maven-release).
  The run publishes the artifacts and tags the commit `v<version>`. A version given by hand overrides
  the computed one.
- **A snapshot:** push a commit whose message contains `[publish-snapshot]`.
- **Commit messages:** PRs are rebase-merged, so every commit lands on `main`. CI checks that each one
  is a Conventional Commit: `<type>(<scope>)!: <summary>`, with type `feat`, `fix`, `docs`, `style`,
  `refactor`, `perf`, `test`, `build`, `ci`, `chore` or `revert`.

Published: `mojo-io-parent`, `mojo-io-spec`, `mojo-io-cache`, `mojo-io-maven-extension` and the REAPI
adapter `mojo-io-reapi`, all in group `dev.frontseat.mojoio`.

GitHub Packages asks for a token even to read, and a core extension is resolved from the repositories
of your `settings.xml`, so a project that uses a published version needs:

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USER</username>
      <password>A_TOKEN_WITH_read:packages</password>
    </server>
  </servers>
  <profiles>
    <profile>
      <id>mojo-io</id>
      <repositories>
        <repository>
          <id>github</id>
          <url>https://maven.pkg.github.com/frontseat-dev/frontseat-maven</url>
          <snapshots><enabled>true</enabled></snapshots>
        </repository>
      </repositories>
      <pluginRepositories>
        <pluginRepository>
          <id>github</id>
          <url>https://maven.pkg.github.com/frontseat-dev/frontseat-maven</url>
          <snapshots><enabled>true</enabled></snapshots>
        </pluginRepository>
      </pluginRepositories>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>mojo-io</activeProfile>
  </activeProfiles>
</settings>
```

## Repository layout

| Module | |
|---|---|
| `mojo-io-spec` | The `plugin-io.xml` format: model, reader, XSD. JDK only, so it can be adopted anywhere. |
| `mojo-io-cache` | Maven-agnostic: hashing, cache keys, Ant patterns, the local content-addressed store. |
| `mojo-io-maven-extension` | The core extension: execution strategy, declaration lookup, project-state diff, prerequisites. |
| `mojo-io-it` | Integration tests that fork real Maven 3.9 / 4 builds of the projects in `src/it/projects`. |
| `mise.toml`, `mise-tasks/` | Toolchain and tasks. |
| `adapters/nx` | [@mojo-io/nx](adapters/nx/README.md): an Nx plugin built on the graph. An adapter outside the core, with an e2e proof of correctness and speed. |
| `adapters/reapi` | [REAPI adapter](adapters/reapi/README.md): runs each task as a Remote Execution API action. Proven end to end against Buildbarn. |
| `examples/plugin-io` | Declarations to copy into a project (static-analysis checks). |
| `docs/` | [Design notes](docs/design.md), [performance](docs/performance.md), [a real-project trial](docs/real-projects.md), [versioning](docs/versioning.md), formats ([plugin-io](docs/plugin-io.md), [task graph](docs/task-graph.md), [state](docs/state-format.md)), [Maven 4 proposal](docs/maven4-proposal.md). |

## Development

The ITs copy `mojo-io-it/src/it/projects/<name>` to `mojo-io-it/target/it/<test>/<checkout>/`
and keep every forked build's log there (`build-*.log`, which starts with the Maven version).
Requires JDK 17+. Plain `mvn verify` also works if you'd rather not use mise.
