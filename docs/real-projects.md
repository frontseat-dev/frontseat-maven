# Trying mojo-io on real projects

The samples and the generated benchmark only use plugins that are declared. A real build shows what
happens with everything else. The main trial is on **Apache Commons Numbers** (commit `6747895`,
14 modules, `commons-parent` 105): 374 mojo executions of 30 plugin goals per `mvn verify`.

Setup: `.mvn/extensions.xml` with the mojo-io extension, Maven 3.9.11, JDK 21, 4 cores.

The declarations a project copies into `.mvn/plugin-io/` are in [`examples/plugin-io`](../examples/plugin-io):
`static-analysis.xml`, `common-plugins.xml` and, for the parent's one antrun script,
`apache-commons-parent.xml`.

## What the trial found

1. **Built-in declarations cover the build steps, not the checks.** Compile, resources, tests and
   jars are declared, but SpotBugs, checkstyle and PMD are the most expensive mojos of this build.
   Their declarations are in
   [`examples/plugin-io/static-analysis.xml`](../examples/plugin-io/static-analysis.xml).
2. **Checks that fork a goal needed support in mojo-io.** `spotbugs:check` and `pmd:check` fork the
   goal that does the analysis. Such a mojo is one unit with its forked executions in the task graph
   (their configuration joins its fingerprint, their inputs and outputs are its own), provided each
   of them is declared. `ForkedGoalsIT` covers it.
3. **`remoteProjectRepositories`** is a Maven object like `remoteRepositories`, not configuration. It
   made any mojo with that parameter uncacheable; it's ignored.
4. **A wrong declaration can hide a failure.** The first checkstyle declaration listed the
   `sourceDirectories` parameter as the input. That parameter is empty by default (the mojo then
   reads the project's source roots), so the sources weren't an input, and a violation was restored
   as a pass. The fix was to also declare `${project.compileSourceRoots}`.
   - A build system's sandbox and write check catch missing *outputs*; nothing catches a missing
     *input*.
   - Check a new declaration by reading its inputs in the exported task graph and by changing an
     input to see the task run again ([plugin-io](plugin-io.md#checking-a-declaration)).
   - This is why these three declarations are examples to copy, not built in.
5. **Any change to a class file invalidates dependents.** Adding a comment line to a core class shifts
   line numbers in the class file, so the 8 modules compiled against it recompile and re-run
   their tests. Class files are compared by content, not by their API.
6. **A task is cacheable only if every mojo in it is declared.** With the built-in declarations that
   was 13 of 55 stage tasks: cheap undeclared mojos such as `enforcer:enforce` or `bundle:manifest`
   sit in the compile and package stages of every module. With the first round of common-plugin
   declarations it was 41 of 55; with every plugin declared, including the aggregator in each package
   stage, it's **55 of 55**.
   - Declaring those plugins needed three new input kinds: the project's own files (`projectFiles`),
     its effective model (`projectModel`) and the Maven version (`mavenVersion`).
   - Parameters holding the build itself (session, project, repositories) are recognised by type,
     whatever they're called.

## On a remote execution grid (Buildbarn)

The same project through the [REAPI adapter](../adapters/reapi/README.md), one action per stage, plain
`mvn` on the worker:

| Build | Time | Actions executed |
|---|---|---|
| Built-in declarations only: first build | 279s | 55 of 55 |
| Built-in declarations only: second build, nothing changed | 283s | 55 of 55 |
| With the first round of example declarations: first build | 270s | 55 of 55 |
| With the first round of example declarations: second build | 52s | 14 of 55 |
| With every plugin declared: first build | 284s | 55 of 55 |
| With every plugin declared: second build, nothing changed | **6s** | 0 of 55 |

Plain `mvn clean verify` takes 132s on the same machine. The downloaded outputs are identical to
plain Maven (1,334 files: classes, test classes, the contents of every jar and the test results).
What it showed:

7. **Task ordering needed more than Maven 4's phase links.** Two modules failed until the task graph
   learned two rules:
   - a task waits for the reactor dependencies its own mojos make Maven resolve (a check in the
     compile stage that resolves the test classpath needs the test-scope dependencies built);
   - a dependency on an attached artifact (here another module's `tests` jar; in general any
     classifier, `sources`, or an archive like a `war`) waits for that module's package task.
8. **A task-level cache is all or nothing per task.** With only the built-in declarations, 42 of the
   55 tasks contained an undeclared mojo, and nothing was cached on the second build: the 13 declared
   tasks sit downstream of undeclared ones whose outputs differ from run to run. With the first round
   of example declarations, only the 14 package stages still ran (each held an undeclared aggregator).
   With every plugin declared, a second build runs nothing.
9. **The build can't run offline.** `cyclonedx:makeAggregateBom` refuses to run in offline mode, so the
   workers need network access.
10. **A cold remote build is slower than a local one** (270s against 132s): every action starts a JVM.

## Nothing is left undeclared

The last three plugins are in `common-plugins.xml` too:
- `cyclonedx:makeAggregateBom`, an **aggregator**: one execution that describes every project of the
  build. An aggregator is cacheable only when its declaration has a `reactorModels` input (the
  effective models of all projects).
- `spdx:createSPDX`, which writes the SPDX document.
- `moditect:add-module-info`, which rewrites the project's jar in place: the jar as `jar:jar` left it
  is the input, the rewritten jar the (observed) output.

## More projects

The same declarations (the built-in ones plus `static-analysis.xml` and `common-plugins.xml`) on
other projects:

| Project | Modules | Still undeclared |
|---|---|---|
| Apache Commons Numbers | 14 | nothing |
| Apache Commons RNG | 7 | `revapi:check` |
| Google Gson (without `test-shrinker`, which needs a repository this machine can't reach) | 6 | `spotless:check`, `templating:filter-sources`, `protobuf:generate-test`, `proguard:proguard`, `bnd:bnd-process` |
| Spring PetClinic, with [`spring-boot.xml`](../examples/plugin-io/spring-boot.xml) | 1 | `git-commit-id:revision` |
| MapStruct examples (annotation processors: MapStruct, Lombok) | 25 | Kotlin and protobuf goals |

Notes:
- **Annotation processors** (`annotationProcessorPaths`) count by their coordinates, with a version
  from dependency management resolved the way the compiler resolves it; a SNAPSHOT or range version
  makes the compile not cacheable. A processor built in the same reactor is marked as such, and the
  compile task waits for that project's tasks. In the MapStruct Kotlin example, MapStruct writes a
  fresh `@Generated` date into the sources it generates through kapt, so the Java compile's inputs
  change on every build.
- **Task-level cacheability** on these projects: MapStruct examples 60 of 63 stage tasks (the rest
  hold Kotlin and protobuf goals), PetClinic 2 of 3 (`git-commit-id:revision` reads the git
  repository), Tycho's RCP demo 1 of 14 (see below).
- **Gson's `bnd:bnd-process`** writes a manifest whose attribute order depends on the manifest it
  finds from the previous build. A non-clean rebuild therefore differs from a clean build, with or
  without mojo-io. A build system that gives every task a fresh input root doesn't see that.
- **`artifact:check-buildplan`** depends on the goals of the build (a `sessionGoals` input), so its
  fingerprint differs between `verify` and `clean verify`.

## Eclipse Tycho

Tycho builds Eclipse plugins, features and products with its own packaging types, a target platform
resolved from p2 repositories, and its own plugins (`tycho-compiler`, `tycho-packaging`,
`tycho-p2-*`). Trial on Tycho's RCP demo (`demo/itp04-rcp` in eclipse-tycho/tycho, Tycho 5.0.1;
7 projects: a bundle, two features, root files, a p2 repository with a product), with
[`examples/plugin-io/tycho.xml`](../examples/plugin-io/tycho.xml) and a fixed qualifier
(`-DforceContextQualifier=v1`).

- **Resolved bundles:** Tycho adds the bundles it resolved as system-scoped dependencies
  (`p2.eclipse.plugin:…`) with fully qualified versions, but their files are placeholders with random
  names until Tycho fetches them. The project model input ignores the placeholder paths; the bundles
  count by their versions, which p2 never reuses for other content.
- **Output directories:** a `directory` output can be an expression, because Tycho's compiler has no
  `outputDirectory` parameter.
- **Build qualifiers are timestamps by default.** Tycho writes the build time into every manifest and
  `feature.xml`, so each build is new. Use a fixed qualifier or one from git
  (`tycho-buildtimestamp-jgit`).
- **Still undeclared:** packaging and p2 publishing mojos (`package-plugin`, `package-feature`,
  `p2-metadata`, `assemble-repository`, `materialize-products`, …) and `build-qualifier`, which reads
  the clock. So a build system can't cache a package stage of a Tycho project yet. A bundle's compile
  and test tasks are cacheable, including OSGi declarative services (`tycho-ds`) and PDE's automatic
  manifest (`tycho-bnd`).

### Task by task, and on Buildbarn

Every task of the demo also builds as its own Maven call (as Nx runs it), and on Buildbarn through
the REAPI adapter, each action in its own sandbox. Outputs match plain Maven: the files that differ
are the ones that differ between two plain builds too (p2 profile timestamps, archives repacked with
new times, p2's record of the repositories it read). That took:

- **Reactor references.** The target definition project is named only in the parent's
  `target-platform-configuration`, and Tycho adds required bundles, features and units as
  dependencies while loading projects, after Maven sorted the reactor. The graph lists both as
  `reactorReferences` for `-pl`, with task edges (docs/task-graph.md).
- **Files read while loading.** Tycho reads manifests, `feature.xml` and the target definition before
  any mojo runs. `tycho.xml` lists them in a `projectLoad` element, exported as `loadFiles`.
- **Source folders from `build.properties`.** Tycho sets them as compile source roots while the build
  runs, so the graph, exported beforehand, would see Maven's default `src/main/java`: a REAPI action
  compiled nothing, and Nx would not have hashed the sources. The compiler declarations name the
  project's own files instead.
- **State in Tycho's context.** `tycho-ds` hands the `Service-Component` header to `package-plugin`
  through Tycho's own project context. Text values there are recorded and replayed
  (docs/state-format.md).
- **State deleted by a clean.** The demo binds `clean` to `validate`, after the qualifier was computed:
  state files recorded earlier in the same build are written again.
- **Absolute paths in outputs.** See the REAPI adapter's README.

## Reproducing it

```bash
git clone https://github.com/apache/commons-numbers.git && cd commons-numbers
mkdir -p .mvn/plugin-io
cat > .mvn/extensions.xml <<'XML'
<extensions><extension>
  <groupId>dev.frontseat.mojoio</groupId><artifactId>mojo-io-maven-extension</artifactId><version>0.1.0-SNAPSHOT</version>
</extension></extensions>
XML
cp <mojo-io>/examples/plugin-io/*.xml .mvn/plugin-io/
mvn verify -Dmojoio.graph.output=graph.json   # the task graph: which tasks are cacheable, and why not
```

Then build it through a build system, for example the [Nx adapter](../adapters/nx/README.md) or the
[REAPI adapter](../adapters/reapi/README.md).
