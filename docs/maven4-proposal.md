# Proposal: making Maven 4 friendly to monorepo tooling

**Status:** draft for discussion with the Apache Maven project.
**Prototype:** this repository. The same extension jar runs unchanged on Maven 3.9.11 and
4.0.0-rc-7, and both lines are covered by integration tests (`mise run ci`).

## Problem

Monorepo tools (Nx, Bazel, Turborepo, Gradle's build cache, Frontseat) work with **tasks** that have
**declared inputs and outputs**. With that they can skip work, restore results from a shared
cache, run only what a change affects, and schedule tasks across a repository.

Maven has the unit (a mojo execution) but none of the metadata:

1. **Mojo I/O is implicit.** What a mojo reads and writes is buried in its implementation. Tools
   have to guess, or treat the whole module as one opaque task.
2. **Side effects are invisible.** Mojos change the in-memory `MavenProject`: the compiler adds a
   generated-source root, JaCoCo sets `argLine`, packaging sets the artifact file. A cache that
   restores files but not these changes produces a different build.
3. **There is no supported hook.** Caching extensions rely on internal classes
   (`MojosExecutionStrategy`, `LifecycleExecutionPlanCalculator`, `LifecycleDependencyResolver`,
   `PluginParameterExpressionEvaluator`).
4. **Goals have no prerequisites.** `mvn surefire:test` on a fresh checkout fails instead of
   running, or restoring, the compile it depends on.

`maven-build-cache-extension` shows that the demand is real. It caches whole modules, configured per
project. This proposal targets mojo granularity, with I/O declared once by the plugin author for
every user. The metadata would serve the build-cache extension equally well.

## Proposal

All parts are opt-in and additive. A build that doesn't use them behaves exactly as today.

### P1: I/O metadata in the plugin descriptor

Let plugin authors declare inputs and outputs per **parameter**, so the metadata holds for every
configuration. In the Maven 4 plugin API this could be a few annotations next to the existing
`org.apache.maven.api.plugin.annotations.Parameter`:

```java
@Mojo(name = "compile", defaultPhase = "compile")
public class CompilerMojo implements org.apache.maven.api.plugin.Mojo {
    @Parameter(defaultValue = "${project.compileSourceRoots}") @InputFiles  List<Path> compileSourceRoots;
    @Parameter(defaultValue = "${project.compileClasspathElements}") @Classpath(excludeSelf = true) List<Path> compilePath;
    @Parameter(defaultValue = "${project.build.outputDirectory}") @OutputDirectory(includes = "**/*.class") Path outputDirectory;
    @Parameter(defaultValue = "false") @Internal boolean verbose;   // cannot affect outputs
}
```

`maven-plugin-plugin` would record these in `plugin.xml` alongside each `<parameter>`, and
`org.apache.maven.api.plugin.descriptor.Parameter` would gain the matching fields. A mojo-level flag
`cacheable = false` with a reason covers mojos with uncapturable effects.

**Bridge, available today:** [`plugin-io.xml`](plugin-io.md), with the same semantics as a separate
file. Plugins can ship it as `META-INF/maven/plugin-io.xml` without waiting for a Maven release, and
projects can override it in `.mvn/plugin-io/`. Its core kinds map one-to-one onto the annotations
(`files` → `@InputFiles`, `classpath` → `@Classpath`, `directory` → `@OutputDirectory`,
`ignoredParameters` → `@Internal`). The derived kinds (`testClasspath`, `resources`,
`projectProperty`, `javaagentDestFiles`) exist only because today's plugins compute those values
internally. Exposing them as parameters retires the derived kinds.

### P2: record project mutations through `ProjectManager`

This removes the biggest risk in mojo caching. In Maven 4 the API `Project` is immutable, and
mutations go through `org.apache.maven.api.services.ProjectManager`: `addSourceRoot`,
`setProperty`, `attachArtifact`. That makes them observable. If core recorded these calls
per mojo execution as a **project delta**, a cache could store the delta with the outputs and
replay it exactly.

Plugins still on the Maven 3 API mutate `MavenProject` directly. For them, a cache keeps the approach
this prototype uses: snapshot before, diff after, and refuse to cache changes it can't replay. The
delta API makes v4 plugins exact and leaves v3 plugins safe.

### P3: an execution-strategy SPI in the API

Promote the hook to `org.apache.maven.api.spi`: something like an `ExecutionInterceptor` that
receives, per mojo execution, the **evaluated** parameters, the resolved dependencies and the I/O
metadata. It returns either "ran" (with the delta) or "restored" (with a delta to apply). Core
already does each of these steps; the SPI exposes them in a stable form. Caching extensions, remote
execution and build scans could then share one supported hook instead of internal classes.

### P4: goal prerequisites and an exportable task graph

- **Prerequisites (opt-in):** a mojo may declare the phase that must have run before it when invoked
  directly (as `plugin.xml` already has `<phase>`). `mvn surefire:test` then runs or restores
  compile first.
- **Graph export:** a dry-run mode that prints the per-project mojo execution plan with each
  execution's declared inputs, outputs and inter-module edges as JSON. External orchestrators (an Nx
  project-graph plugin, Frontseat) can then schedule and cache Maven tasks without running Maven
  to discover them.

### P5: portable paths

Cache keys must not depend on where a checkout lives. Maven 4 already exposes the root directory
(`session.rootDirectory`). Standardizing how evaluated paths are relativized (module, root, local
repository) lets independent tools compute the same keys. This prototype uses
`@@BASEDIR@@`, `@@ROOT@@` and `@@M2REPO@@` tokens. It also means modules with identical inputs share
cache entries.

## Evidence from the prototype

- One extension jar runs on **Maven 3.9.11 and 4.0.0-rc-7**. On a fresh checkout at another path,
  `mvn surefire:test` restores resources, compile, testCompile and `jacoco:prepare-agent`, then runs
  the tests with the relocated agent.
- Six goals are described entirely by `plugin-io.xml` (`builtin-plugin-io.xml`). The integration
  tests found three subtle errors that P1–P3 would remove by construction:
  - the evaluator ignoring `default-value` fallback;
  - classpath expressions that stay empty until the artifact filter is set;
  - a classpath that contains the mojo's own output.
  See [design notes](design.md).

## Suggested path

1. Publish the `plugin-io.xml` format, and ship declarations in a few plugins (compiler, resources,
   surefire), probably as PRs to those plugins.
2. Teach `maven-plugin-plugin` to generate the same data from annotations (P1).
3. Add the descriptor fields and the `ProjectManager` delta recording to Maven 4 (P1, P2).
4. Design the SPI (P3) with the maintainers of `maven-build-cache-extension`, so both can share it.
5. Add graph export (P4) once the metadata exists.

## Open questions

- Should declarations live in `plugin.xml` (one file) or remain a separate resource (independent
  versioning)?
- How should toolchains enter the key? Today the key uses the JDK that runs Maven.
- Where is the line between I/O declared by the plugin and per-project overrides that bypass it?
