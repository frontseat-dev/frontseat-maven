# Frontseat Maven

A mojo-level build cache for Maven 3.9+, shipped as an ordinary **core extension**. You don't need a
Maven fork or a core patch, and you don't have to change your POMs.

Every mojo execution is wrapped by a custom `MojosExecutionStrategy`. For goals it has a descriptor
for, Frontseat computes a cache key from the plugin, the JDK, the effective configuration and the
content of the declared inputs. On a hit it restores the output files *and* the changes the mojo made to
the `MavenProject` (source roots, properties like `argLine`, the artifact file), then skips the
mojo.

```
[frontseat] HIT   jacoco:prepare-agent (default) @ lib-core [9b7380f2feb5] restored 0 file(s), 1 state change(s)
[frontseat] HIT   resources:resources (default-resources) @ lib-core [b26d37d233dd] restored 1 file(s), 0 state change(s)
[frontseat] HIT   compiler:compile (default-compile) @ lib-core [67d916192e10] restored 1 file(s), 2 state change(s)
[frontseat] HIT   compiler:testCompile (default-testCompile) @ lib-core [134d1b66a470] restored 1 file(s), 1 state change(s)
[frontseat] MISS  surefire:test (default-cli) @ lib-core [60ae2c0f3daa] stored 3 file(s), 0 state change(s)
```

Frontseat works on **Maven 3.9 and Maven 4** from the same jar. What each mojo reads and writes is
declared in [`plugin-io.xml`](docs/plugin-io.md), a small format with no Maven dependency.
Plugins can ship it, projects can override it, and Maven 4 could adopt it; see the
[proposal](docs/maven4-proposal.md).

## Status

First slice, working end to end on Maven 3.9.11 and 4.0.0-rc-7:

| Goal | Inputs (besides config) | Outputs | Project state replayed |
|---|---|---|---|
| `resources:resources`, `resources:testResources` | selected resource files; project/user props when filtering | copied files | – |
| `compiler:compile`, `compiler:testCompile` | source roots, classpath (by content) | `**/*.class`, generated sources | generated source root, artifact file |
| `surefire:test` | test classpath, classes, test classes | `surefire-reports/**`, JaCoCo `destfile` | – |
| `jacoco:prepare-agent` | current `argLine` | – | `argLine` |

Mojos nobody declares always run normally. If anything unexpected happens while computing a key or
storing an entry, the mojo just runs.

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
    <groupId>dev.frontseat.maven</groupId>
    <artifactId>frontseat-maven-extension</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </extension>
</extensions>
```

Goals invoked directly get their prerequisites. On a fresh checkout, `mvn surefire:test` first runs
or restores the lifecycle up to `process-test-classes`, then runs the tests.

To declare or override a mojo's I/O for one project, for example to stop caching tests that hit a
live service, drop a file in `.mvn/plugin-io/`:

```xml
<plugin-io xmlns="https://frontseat.dev/xsd/plugin-io/1.0">
  <plugin groupId="org.apache.maven.plugins" artifactId="maven-surefire-plugin">
    <mojo goal="test" cacheable="false" reason="integration tests hit a live service"/>
  </plugin>
</plugin-io>
```

### Settings

| Property | Default | |
|---|---|---|
| `frontseat.cache.enabled` | `true` | Master switch. |
| `frontseat.cache.dir` | `~/.m2/frontseat-cache` | Local cache location. |
| `frontseat.cache.read` / `frontseat.cache.write` | `true` | E.g. `write=false` for a read-only CI consumer. |
| `frontseat.prerequisites` | `true` | Expand directly-invoked goals with their lifecycle prerequisites. |

### Why did it miss?

Every cacheable execution writes its key's inputs, and which declaration was used, to
`target/frontseat/<plugin>-<goal>-<executionId>.key.txt`. The cache keeps a copy per key under
`<cache>/keys/`. To see which input changed, diff two of them.

## Repository layout

| Module | |
|---|---|
| `frontseat-plugin-io` | The `plugin-io.xml` format: model, reader, XSD. JDK only, so it can be adopted anywhere. |
| `frontseat-cache-core` | Maven-agnostic: hashing, cache keys, Ant patterns, the local content-addressed store. |
| `frontseat-maven-extension` | The core extension: execution strategy, declaration lookup, project-state diff, prerequisites. |
| `frontseat-it` | Integration tests that fork real Maven 3.9 / 4 builds of the projects in `src/it/projects`. |
| `mise.toml`, `mise-tasks/` | Toolchain and tasks. |
| `docs/` | [Design notes](docs/design.md), [plugin-io format](docs/plugin-io.md), [Maven 4 proposal](docs/maven4-proposal.md). |

## Development

The ITs copy `frontseat-it/src/it/projects/<name>` to `frontseat-it/target/it/<test>/<checkout>/`
and keep every forked build's log there (`build-*.log`, which starts with the Maven version).
Requires JDK 17+. Plain `mvn verify` also works if you'd rather not use mise.
