# Trying mojo-io on a real project

The samples and the generated benchmark only use plugins that are declared. A real build shows what
happens with everything else. This is a trial on **Apache Commons Numbers** (commit `6747895`,
14 modules, `commons-parent` 105): 374 mojo executions of 30 plugin goals per `mvn verify`.

Setup: `.mvn/extensions.xml` with the mojo-io extension, Maven 3.9.11, JDK 21, 4 cores. Plain Maven
means the same command with `-Dmojoio.enabled=false`.

## Results

| Build (`mvn clean verify`) | Time | Executions restored |
|---|---|---|
| Plain Maven | 132s | |
| mojo-io, empty cache | 123s–134s | 0 |
| mojo-io, warm, built-in declarations only (first trial) | 83s | 112 of 374 |
| mojo-io, warm, plus the three check declarations (checkstyle, spotbugs, pmd) | 16s | 154 of 374 |
| mojo-io, warm, plus the common-plugin declarations (first round) | 9s | 334 of 374 |
| mojo-io, warm, with every plugin of the build declared | **5s** | 374 of 374 |
| Fresh checkout at another path, shared cache (three check declarations) | 15s | 154 of 374 |
| After a real change in the core module (new method and test; 9 modules depend on it) | 82s (plain: 125s) | 109 |
| After changing the checkstyle suppressions file | 35s | all but the 14 checkstyle checks |
| After changing the SpotBugs exclude filter | 49s | all but the 14 SpotBugs checks |

The declarations a project copies into `.mvn/plugin-io/` are in [`examples/plugin-io`](../examples/plugin-io):
`static-analysis.xml`, `common-plugins.xml` and, for the parent's one antrun script,
`apache-commons-parent.xml`.

**Correctness.** Classes, test classes, the contents of every jar and the test results (1,334 files)
were compared with a plain Maven build of the same sources:
- after a warm build (with each set of declarations): identical;
- after restoring a fresh checkout at another path: identical;
- after the real change: identical.

Each declaration was also checked the other way round, by breaking what it guards:

| Change | Result |
|---|---|
| A checkstyle violation in a source file | Build fails. |
| A file without a license header | `apache-rat:check` fails. |
| A binary-incompatible change to a public method | `japicmp:cmp` fails. |
| A property added to the parent POM | The mojos that read the project model re-run in every module (enforcer, check-buildplan, bundle manifest, …); compile and tests stay restored. |
| `changes.xml`, a `LICENSE` file, the checkstyle or SpotBugs configuration | Exactly the mojos that read them re-run, plus what depends on their outputs. |

## What the trial found

1. **Built-in declarations cover the build steps, not the checks.** Compile, resources, tests and
   jars were all restored, but the warm build still took 83s: SpotBugs (33s), checkstyle (24s) and
   PMD (12s) ran every time. Declaring them in the project brought it to 16s. The declarations are in
   [`examples/plugin-io/static-analysis.xml`](../examples/plugin-io/static-analysis.xml).
2. **Checks that fork a goal needed support in mojo-io.** `spotbugs:check` and `pmd:check` fork the
   goal that does the analysis. Such a mojo is now cached as one unit with its forked executions
   (their configuration and inputs are in its key), provided each of them is declared.
   `ForkedGoalsIT` covers it.
3. **`remoteProjectRepositories`** is a Maven object like `remoteRepositories`, not configuration. It
   made any mojo with that parameter uncacheable; it's now ignored.
4. **A wrong declaration can hide a failure.** The first checkstyle declaration listed the
   `sourceDirectories` parameter as the input. That parameter is empty by default (the mojo then
   reads the project's source roots), so the sources weren't in the key, and a violation was restored
   as a pass. The fix was to also declare `${project.compileSourceRoots}`.
   - Strict mode catches missing *outputs*; nothing catches a missing *input*.
   - Check a new declaration by reading its key manifest
     (`target/mojo-io/<plugin>-<goal>-<execution>.key.txt`) and by changing an input to see it miss.
   - This is why these three declarations are examples to copy, not built in.
5. **Any change to a class file invalidates dependents.** Adding a comment line to a core class shifts
   line numbers in the class file, so the 8 modules compiled against it recompiled and re-ran
   their tests. mojo-io compares class files by content; it doesn't compare their API.
6. **Tools that cache whole tasks need more declarations than mojo-io's own cache does.** A task is
   cacheable only if every mojo in it is declared. With the built-in declarations that was 13 of 55
   stage tasks: cheap undeclared mojos such as `enforcer:enforce` or `bundle:manifest` sit in the
   compile and package stages of every module. With the first round of common-plugin declarations
   it was 41 of 55; with every plugin declared, including the aggregator in each package stage, it's
   **55 of 55**.
   - Declaring those plugins needed three new input kinds: the project's own files (`projectFiles`),
     its effective model (`projectModel`) and the Maven version (`mavenVersion`).
   - Parameters holding the build itself (session, project, repositories) are now recognised by type,
     whatever they're called; before, they made a mojo uncacheable.

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

The downloaded outputs are identical to plain Maven (the same 1,334 files). What it showed:

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
  build. An aggregator is cached only when its declaration has a `reactorModels` input (the effective
  models of all projects). After a dependency change in one module, the aggregate BOM at the root was
  rebuilt and is identical to plain Maven's.
- `spdx:createSPDX`: the generated SPDX document is identical to plain Maven's.
- `moditect:add-module-info`, which rewrites the project's jar in place: the jar as `jar:jar` left it
  is the input, the rewritten jar the output.

Declaring them exposed one more bug in mojo-io:

11. **The test classpath was not a reliable input.** `testClasspath` read the dependencies Maven had
    resolved for whichever mojo ran last. For a mojo that asks for no resolution itself, that was
    empty after mojos that had really run, and filled after mojos that had been restored: unstable
    keys, and dependencies sometimes missing from the key. The input now resolves the classpath
    itself and leaves the project as it found it. `DeclaredInputsIT` reproduces the old behaviour.

## More projects

The same trial, scripted: a plain build, then mojo-io with an empty cache, two warm clean builds and
a non-clean rebuild, with outputs compared against plain Maven after the clean and after the
non-clean build. Declarations used: the built-in ones plus `static-analysis.xml` and
`common-plugins.xml`.

| Project | Modules | Plain `clean verify` | mojo-io, empty cache | mojo-io, warm | Outputs vs plain Maven | Still undeclared |
|---|---|---|---|---|---|---|
| Apache Commons Numbers | 14 | 132s | 124s | 5s | identical (1,334 files) | nothing |
| Apache Commons RNG | 7 | 179s | 189s | 9s | identical (1,918 files) | `revapi:check` |
| Google Gson (without `test-shrinker`, which needs a repository this machine can't reach) | 6 | 50s | 51s | 12s | identical (1,744 files) | `spotless:check`, `templating:filter-sources`, `protobuf:generate-test`, `proguard:proguard`, `bnd:bnd-process` |
| Spring PetClinic, with [`spring-boot.xml`](../examples/plugin-io/spring-boot.xml) | 1 | 29s | 34s | 7–8s | identical (410 files, the executable jar included) | `git-commit-id:revision` |
| MapStruct examples (annotation processors: MapStruct, Lombok) | 25 | 23s | 24s | 8s | identical (452 files) | Kotlin and protobuf goals |

Notes:
- **Annotation processors** (`annotationProcessorPaths`) used to make the compiler uncacheable. Each
  processor now counts by its coordinates, with a version from dependency management resolved the way
  the compiler resolves it; a SNAPSHOT or range version still runs uncached. On the MapStruct examples
  21 compile executions are restored. The Kotlin example's Java compile misses on every build, and
  rightly: MapStruct writes a fresh `@Generated` date into the sources it generates through kapt.
- **Spring PetClinic**: `spring-boot:repackage` is restored as an executable jar (94 bundled libraries,
  plus the `.original`). For a build system that caches whole tasks, 2 of its 3 stage tasks are
  cacheable: `git-commit-id:revision` (it reads the git repository) stays undeclared.
- **Task-level caching** (Nx, a remote execution grid) on these projects: MapStruct examples 60 of 63
  stage tasks cacheable (the rest hold Kotlin and protobuf goals), PetClinic 2 of 3, Tycho's RCP demo
  1 of 14 (see below).
- **Gson's `bnd:bnd-process`** writes a manifest whose attribute order depends on the manifest it
  finds from the previous build. A non-clean rebuild therefore differs from a clean build, with or
  without mojo-io, and re-runs what reads the classes directory. Compared like for like, the outputs
  are identical.
- **A non-clean rebuild after `clean verify`** re-runs `artifact:check-buildplan`: its result depends on
  the goals of the build, which are part of its key.

## Eclipse Tycho

Tycho builds Eclipse plugins, features and products with its own packaging types, a target platform
resolved from p2 repositories, and its own plugins (`tycho-compiler`, `tycho-packaging`,
`tycho-p2-*`). Trial on Tycho's RCP demo (`demo/itp04-rcp` in eclipse-tycho/tycho, Tycho 5.0.1;
7 projects: a bundle, two features, root files, a p2 repository with a product), with
[`examples/plugin-io/tycho.xml`](../examples/plugin-io/tycho.xml) and a fixed qualifier
(`-DforceContextQualifier=v1`):

| Build | Plain Maven | mojo-io |
|---|---|---|
| `clean verify` | 22s | 23s, empty cache |
| `clean verify`, warm | | 22–25s, all 19 declared executions restored |

- **Correct:** jars, `feature.xml` files and test results are identical to plain Maven after clean and
  non-clean builds.
- **Invalidation:** a changed Java source and a changed `MANIFEST.MF` (a new required bundle) each make
  `tycho-compiler:compile` miss; reverting them hits again.
- **What made it work:**
  - Tycho adds the bundles it resolved as system-scoped dependencies (`p2.eclipse.plugin:…`) with fully
    qualified versions, but their files are placeholders with random names until Tycho fetches them.
    The test classpath input now identifies such bundles by version, and bundles of the reactor by
    content. The project model input ignores the placeholder paths.
  - A `directory` output can be an expression: Tycho's compiler has no `outputDirectory` parameter.
- **No time saved on this demo.** Its compile is tiny, and most of a Tycho build goes to resolving the
  target platform while the projects load, before any mojo runs: no mojo cache can skip that. The
  gain grows with the amount of compiling and testing.
- **Build qualifiers are timestamps by default.** Tycho writes the build time into every manifest and
  `feature.xml`, so each build is new. Use a fixed qualifier or one from git
  (`tycho-buildtimestamp-jgit`).
- **Still undeclared:** packaging and p2 publishing mojos (`package-plugin`, `package-feature`,
  `p2-metadata`, `assemble-repository`, `materialize-products`, …) and `build-qualifier`, which reads
  the clock. They're cheap next to compiling and testing, but a build system that caches whole tasks
  can't cache a package stage of a Tycho project yet. A bundle's compile and test tasks are cacheable,
  including OSGi declarative services (`tycho-ds`) and PDE's automatic manifest (`tycho-bnd`).

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
  compiled nothing, and Nx would not have hashed the sources. The compiler declarations hash the
  project's own files instead.
- **State in Tycho's context.** `tycho-ds` hands the `Service-Component` header to `package-plugin`
  through Tycho's own project context. Text values there are now recorded and replayed
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
mvn clean verify -Dmojoio.enabled=false     # plain
mvn clean verify                            # fills the cache
mvn clean verify                            # warm
```
