# mojo-io

**Per-mojo inputs, outputs and project state for Maven: tool-agnostic.**

mojo-io makes every Maven mojo execution a well-defined task: what it reads, what it writes, and
what it changes in the in-memory project. It is neutral infrastructure. A build cache, a monorepo
orchestrator (Nx, Bazel, Frontseat, …) or Maven itself can consume it; mojo-io knows about none of
them. Tool integrations live outside it, as adapters.

## Architecture

| Layer | What | Knows about tools? |
|---|---|---|
| **Specs** (`mojo-io-spec`) | Versioned, documented formats: [`plugin-io.xml`](docs/plugin-io.md) declares mojo I/O. A task-graph format and a project-state format are next. JDK-only, no Maven dependency. | No |
| **Maven extension** (`mojo-io-maven-extension`) | An ordinary core extension (`.mvn/extensions.xml`) for Maven 3.9 and 4. It resolves each execution's inputs and outputs, captures and replays project state, and includes an optional local cache. | No |
| **Adapters** (outside this repo's core) | Nx, Frontseat, Bazel, CI caches, … Each translates the neutral formats for one tool. | Yes, one tool each |

You don't need a Maven fork or a core patch, and you don't have to change your POMs.

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
- ⛔ **not cacheable**: has side effects outside the project, so it always runs, by design.
- ➖ **not yet**: no declaration, so it always runs.

| Plugin | Goal | Status | IT-verified version | Parameters checked in | Restores | Notes |
|---|---|---|---|---|---|---|
| `maven-resources-plugin` | `resources`, `testResources` | ✅ | 3.3.1 | 3.1.0, 3.2.0, 3.3.0, 3.3.1, 4.0.0-beta-1 | copied files | Filtering hashes project + `-D` properties, not system properties or env. `${maven.build.timestamp}` in a filtered file means it misses every time. |
| `maven-compiler-plugin` | `compile`, `testCompile` | ✅ | 3.13.0 | 3.8.1, 3.10.1, 3.11.0, 3.12.1, 3.13.0, 3.14.0, 4.0.0-beta-2 | classes, generated sources, source root, artifact file | Configuring `annotationProcessorPaths` makes it uncacheable (unsupported parameter type). Toolchains are not in the key. |
| `maven-surefire-plugin` | `test` | ✅ | 3.5.2 | 2.22.2, 3.0.0, 3.2.5, 3.5.2, 3.5.4 | reports, JaCoCo `destfile` | Tests that use the network, a DB, env vars or files outside the project get cached too: override with `.mvn/plugin-io/`. With `testFailureIgnore`, failures are cached. |
| `jacoco-maven-plugin` | `prepare-agent` | ✅ | 0.8.12 | 0.8.7, 0.8.10, 0.8.12, 0.8.13, 0.8.14 | `argLine` | A hit requires the agent jar in the local repository; otherwise it counts as a miss. |
| `jacoco-maven-plugin` | `report` | ➖ | | | | Next candidate: inputs `jacoco.exec` + classes, output `site/jacoco`. |
| `maven-jar-plugin` | `jar` | ➖ | | | | Runs, but cheap. Downstream modules no longer miss when it re-packs (archives are hashed by entries, not timestamps). |
| `maven-failsafe-plugin` | `integration-test`, `verify` | ➖ | | | | Same shape as surefire; usually needs a per-project decision. |
| `maven-source-plugin` | `jar` | ➖ | | | | Forks a lifecycle: skipped by design today. `jar-no-fork` is the candidate. |
| `maven-javadoc-plugin` | `javadoc`, `jar` | ➖ | | | | |
| `maven-clean-plugin` | `clean` | ⛔ | | | | Deletes outputs; nothing to cache. |
| `maven-install-plugin` | `install` | ⛔ | | | | Writes to the local repository (outside the project). |
| `maven-deploy-plugin` | `deploy` | ⛔ | | | | Talks to a remote repository. |

Version support is **not enforced at runtime** yet: a built-in declaration applies to any version of
its plugin. A plugin's own `META-INF/maven/plugin-io.xml` and a project's `.mvn/plugin-io/` override
the built-in declarations. To add a row, add the declaration to `builtin-plugin-io.xml`, check its
parameters against the plugin versions, and add an IT before marking it ✅.

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

## Use it in a project

After `mise run install`, add `.mvn/extensions.xml` to any Maven 3.9+ or 4 project:

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
| `mojoio.cache.enabled` | `true` | Master switch. |
| `mojoio.cache.dir` | `~/.m2/mojo-io-cache` | Local cache location. |
| `mojoio.cache.read` / `mojoio.cache.write` | `true` | E.g. `write=false` for a read-only CI consumer. |
| `mojoio.prerequisites` | `true` | Expand directly-invoked goals with their lifecycle prerequisites. |

### Why did it miss?

Every cacheable execution writes its key's inputs, and which declaration was used, to
`target/mojo-io/<plugin>-<goal>-<executionId>.key.txt`. The cache keeps a copy per key under
`<cache>/keys/`. To see which input changed, diff two of them.

## Repository layout

| Module | |
|---|---|
| `mojo-io-spec` | The `plugin-io.xml` format: model, reader, XSD. JDK only, so it can be adopted anywhere. |
| `mojo-io-cache` | Maven-agnostic: hashing, cache keys, Ant patterns, the local content-addressed store. |
| `mojo-io-maven-extension` | The core extension: execution strategy, declaration lookup, project-state diff, prerequisites. |
| `mojo-io-it` | Integration tests that fork real Maven 3.9 / 4 builds of the projects in `src/it/projects`. |
| `mise.toml`, `mise-tasks/` | Toolchain and tasks. |
| `docs/` | [Design notes](docs/design.md), [plugin-io format](docs/plugin-io.md), [Maven 4 proposal](docs/maven4-proposal.md). |

## Development

The ITs copy `mojo-io-it/src/it/projects/<name>` to `mojo-io-it/target/it/<test>/<checkout>/`
and keep every forked build's log there (`build-*.log`, which starts with the Maven version).
Requires JDK 17+. Plain `mvn verify` also works if you'd rather not use mise.
