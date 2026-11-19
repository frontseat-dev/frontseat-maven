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
`FrontseatMojosExecutionStrategy` with `@Priority(10)`, so it wins over `DefaultMojosExecutionStrategy`.
`maven-build-cache-extension` uses the same mechanism. The extension is loaded as a core extension
(`.mvn/extensions.xml` or `-Dmaven.ext.class.path`). Its realm has maven-core as parent, so internal
components such as `LifecycleDependencyResolver` and `LifecycleExecutionPlanCalculator` can be
injected.

## Per execution

1. **Declaration lookup** by `groupId:artifactId:goal` (project → plugin jar → built-in), mapped
   to an executable descriptor. An unknown input/output kind makes the mojo non-cacheable. No descriptor: run. Aggregators and mojos with
   forked executions: run.
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

### Path normalization

`@@BASEDIR@@` (the module), `@@ROOT@@` (the multi-module root) and `@@M2REPO@@` (the local repository)
replace absolute paths in keys and stored state. The longest match wins, and matches must fall on path
boundaries. As a result:

- the same checkout at a different path hits (this is covered by the acceptance IT);
- modules with identical inputs share entries (`jacoco:prepare-agent` is one entry for every module);
- stored `argLine` values are relocated on restore.

### Project state

`ProjectState` captures compile and test source roots, resources, properties, the artifact file and
attached artifacts. Replayable diffs are: added source roots, property set/remove, and the artifact
file. Removed source roots, changed resources and attached artifacts make the execution non-cacheable
instead of being dropped.

**Main risk:** a mojo can change things the diff can't see: static fields, system properties, the
session. That is what descriptors are for. A mojo known to do this gets `notCacheable(reason)`.
We don't guess.

### Prerequisites for direct goals

If *every* execution in the list came from the CLI (`mvn surefire:test`), the strategy computes the
lifecycle plan up to the deepest `prerequisitePhase` of the requested goals and prepends it, minus
the goals already requested. With a warm cache those prerequisites are all restores. Mixed
invocations (`mvn test surefire:test`) are left alone. To turn this off, set
`-Dfrontseat.prerequisites=false`.

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
- **Unrelated `-D` flags.** Filtering hashes user properties, so build plumbing (`frontseat.*`,
  `maven.repo.local`, …) is excluded.

## Known limitations of the first slice

- Stale outputs: if a source is deleted, a later miss can still store the stale `.class` the compiler
  left behind. Strict mode will deal with this.
- Toolchains are ignored. The key uses the JDK running Maven.
- Filtering hashes project and user properties, but not system properties.
- A remote cache would also need plugin dependencies like the JaCoCo agent jar to exist locally.
  Today that case is a miss, not a failure.

## Roadmap

1. **Strict mode**: fail (or warn) on writes outside the declared outputs, by snapshotting `target/`
   around each miss. Also clean declared outputs before a miss.
2. **Remote cache**: an HTTP or REAPI-compatible CAS + action cache behind the same `LocalCache`
   API, with local as an L1 cache.
3. **Graph export**: emit the per-module execution graph with inputs and outputs for Nx or Frontseat
   (proposal P4).
4. **Upstreaming**: offer `plugin-io.xml` to the compiler, resources and surefire plugins, and
   discuss P1–P3 of the [proposal](maven4-proposal.md) with the Maven project.
5. **Declaration coverage**: the long tail of plugins (jar, source, javadoc, failsafe, protobuf,
   build-helper, …), ideally shipped by the plugins themselves.
6. **Edge cases**: forked executions, `@aggregator` mojos, plugins writing outside `target/`.
