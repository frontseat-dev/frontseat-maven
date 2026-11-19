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

## Status

First slice, working end to end. It covers:

| Goal | Inputs (besides config) | Outputs | Project state replayed |
|---|---|---|---|
| `resources:resources`, `resources:testResources` | selected resource files; project/user props when filtering | copied files | – |
| `compiler:compile`, `compiler:testCompile` | source roots, classpath (by content) | `**/*.class`, generated sources | generated source root, artifact file |
| `surefire:test` | test classpath, classes, test classes | `surefire-reports/**`, JaCoCo `destfile` | – |
| `jacoco:prepare-agent` | current `argLine` | – | `argLine` |

Mojos without a descriptor always run normally. Anything unexpected while computing a key or storing
an entry falls back to running the mojo.

## Try it

```bash
./mvnw install
```

Then in any Maven 3.9+ project, add `.mvn/extensions.xml`:

```xml
<extensions>
  <extension>
    <groupId>dev.frontseat.maven</groupId>
    <artifactId>frontseat-maven-extension</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </extension>
</extensions>
```

Or load it without touching the project:

```bash
mvn verify -Dmaven.ext.class.path=frontseat-maven-extension/target/frontseat-maven-extension-0.1.0-SNAPSHOT.jar:frontseat-cache-core/target/frontseat-cache-core-0.1.0-SNAPSHOT.jar
```

Goals invoked directly get their prerequisites. On a fresh checkout, `mvn surefire:test` first runs
or restores the lifecycle up to `process-test-classes`, then runs the tests.

### Settings

| Property | Default | |
|---|---|---|
| `frontseat.cache.enabled` | `true` | Master switch. |
| `frontseat.cache.dir` | `~/.m2/frontseat-cache` | Local cache location. |
| `frontseat.cache.read` / `frontseat.cache.write` | `true` | E.g. `write=false` for a read-only CI consumer. |
| `frontseat.prerequisites` | `true` | Expand directly-invoked goals with their lifecycle prerequisites. |

### Why did it miss?

Every cacheable execution writes its key's inputs to
`target/frontseat/<plugin>-<goal>-<executionId>.key.txt`, and the cache keeps a copy per key under
`<cache>/keys/`. To see which input changed, diff two of them.

## Repository layout

| Module | |
|---|---|
| `frontseat-cache-core` | Maven-agnostic: hashing, cache keys, Ant patterns, the local content-addressed store. |
| `frontseat-maven-extension` | The core extension: execution strategy, descriptors, project-state diff, prerequisite expansion. |
| `frontseat-it` | Integration tests that fork real Maven builds of the sample projects in `src/it/projects`. |
| `docs/` | [Design notes and roadmap](docs/design.md). |

## Development

```bash
./mvnw verify                        # unit tests + integration tests
./mvnw verify -pl frontseat-it -am   # same; -am builds the extension first
```

The ITs copy `frontseat-it/src/it/projects/<name>` to `frontseat-it/target/it/<test>/<checkout>/`
and keep every forked build's log there (`build-*.log`). Requires JDK 17+.
