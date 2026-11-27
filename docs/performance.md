# Performance

Measured with `mise run perf` (`perf/Bench.java`) on a generated reactor:
- 20 modules in a dependency chain;
- 820 classes, one test class and one jar per module;
- offline;
- median of 3 runs on a 4-core container;
- Maven 3.9 and mvnd 1.0.6 (which embeds Maven 3.9).

| Scenario | mvn | mvn + mojo-io | mvnd | mvnd + mojo-io |
|---|---|---|---|---|
| clean verify | 15.7s | | 12.1s | |
| graph export (dry run) | | 1.9s | | 0.2s |
| invocation floor (`-pl m19 -am validate`) | 1.2s | 1.3s | 0.1s | 0.1s |
| one task: `surefire:test` in m19 (`-pl -am -Dmojoio.only`) | | 2.4s | | **0.6s** |

The graph export runs no mojo. The invocation floor runs no mojo of the default lifecycle. The single
task ran with the extension recording and replaying state only, as a build system runs it.

## Build systems: avoid JVM and Maven startup per task

A build system (Nx, Bazel, …) runs one Maven invocation per task. With plain `mvn`, every invocation
pays JVM start, Maven bootstrap and model building: the **invocation floor**, 1.2s here and more
on bigger reactors. Run tasks through **mvnd** instead:
- the floor drops to about 0.1s;
- a single task (`surefire:test` of the last module, against restored upstream outputs) takes 0.6s
  instead of 2.4s.

mojo-io works unchanged under mvnd (`mise run it:mvnd` runs every IT through it). Things to know:
- **Per-build state:** mojo-io keys its per-build state by build session, because one daemon runs many
  builds. (`MavenProject.equals` compares coordinates, so keying by project wrongly treated a module as
  already replayed in the next build.)
- **Parallel modules:** mvnd builds modules in parallel by default. System-property changes are
  therefore not recorded (another module's change could be attributed to the wrong mojo). Every other
  hook works as in a sequential build.
- **Process-lifetime hooks:** an in-JVM server started by a hook lives in the *daemon*, so it outlives
  the invocation that started it. Keep `runTogether` groups in one invocation anyway; under mvnd a
  split doesn't fail, it leaks.
- **Edited `.mvn/plugin-io/` overrides** are re-read in a running daemon.

## Nx (`adapters/nx`)

Measured with `node adapters/nx/e2e/run.mjs` (mvnd for every target, Nx `parallel: 4`, a shared
remote cache through Nx's self-hosted cache protocol). Same 20-module reactor:

| Scenario | Time | Tasks run |
|---|---|---|
| raw `mvn clean verify` | 15.6s | |
| raw `mvnd clean verify` | 12.8s | |
| Nx, fresh checkout + shared cache (all restored) | **1.5s** | 0 |
| Nx after a comment change in m00 | **1.8s** | 2 |
| Nx after a real change in m00 (new class) | 16.1s | 61 of 61 |
| Nx cold, one target per phase (`granularity: phase`) | 32.2s | 120 |
| Nx cold, one target per stage (`granularity: stage`, the default) | **17.5s** | 60 |
| Nx cold, one target per module (`granularity: module`) | **16.6s** | 20 |

The real change re-runs everything because every module of this benchmark depends on `m00`, directly
or transitively, and Nx hashes upstream outputs transitively (as Maven's classpaths are). A change
that leaves the class files identical (the comment) stops at the first module.

Correctness is checked in the same run. Nx's classes, resources, jar contents and test results are
identical to raw Maven:
- cold, at every granularity;
- after restoring a fresh checkout from the shared cache;
- after changes.

### What a Maven invocation costs, and how to pay it less often

A build system starts one Maven invocation per task. Measured through mvnd on this reactor, without
Nx (the same calls, 4 at a time):

- **A warm call costs about 0.1s** before any mojo runs: the mvnd client connecting, Maven setting up
  the reactor (`-am`) and mojo-io replaying state. The mojos themselves cost the same as in one
  reactor build. So the gap to raw Maven is the number of calls:

  | Tasks per module | Calls | Time |
  |---|---|---|
  | 6 (per phase) | 120 | 24–26s |
  | 3 (per stage) | 60 | 16.1s |
  | 1 (per module) | 20 | 11.7s (raw mvnd: 11.1s) |

- **mvnd stops duplicate daemons after 10 idle seconds.** Parallel calls start extra daemons, each a
  cold JVM. With the default `mvnd.duplicateDaemonGracePeriod` they keep dying and restarting during
  a build: the same 120 calls took 23–39s. Pass `-Dmvnd.duplicateDaemonGracePeriod=3h` (the Nx
  adapter does) and only the first build pays for cold daemons.
- **mvnd can report success without building.** Its builder keeps one cancel flag per daemon, set when
  a client connection drops. A build that starts while the previous connection is still being torn
  down can see the flag, skip every project and exit with success. Seen once in several thousand
  calls. With `mojoio.only`, the extension fails such a build and leaves a marker; the Nx adapter and
  the REAPI client retry it once.
- **Maven flags don't help:** `-o`, `-q` and `-nsu` made no measurable difference here.

So: **join executions into fewer tasks** with `mojoio.graph.granularity` (see
[task graph](task-graph.md#granularity)). This works in every build system, including remote
execution (Bazel/REAPI), where an action can't be batched with others and each one starts a cold JVM.
Joining trades cache granularity for fewer invocations.

## Remote execution: Buildbarn (`adapters/reapi`)

Measured with `java adapters/reapi/e2e/Run.java` against a single-machine Buildbarn built from source
(storage, scheduler, one worker running 4 actions at a time, all on the same 4 cores as the client).
Each task is one REAPI action; an upstream `target/` is passed to the next action inside the CAS. Same
20-module reactor:

| Scenario | Time | Actions executed |
|---|---|---|
| raw `mvn clean verify`, on this machine | 13.7s | |
| Fresh checkout, everything from the action cache | **3.9s** | 0 of 60 |
| Cold, `mvnd` on the worker, one action per stage | 19.9s | 60 |
| Cold, `mvnd` on the worker, one action per module | 17.3s | 20 |
| Cold, `mvn` on the worker, one action per stage | 104.9s | 60 |
| Cold, `mvn` on the worker, one action per module | 73.6s | 20 |
| After a real change in m00, `mvn` on the worker | 104.8s | 60 of 60 |

Outputs are identical to raw Maven in every row (checked in the same run).

- **A cold JVM per action dominates.** With plain `mvn`, an action costs 2–5s before any mojo runs,
  where a call through mvnd costs 0.1s. On a grid, keep a warm Maven process on the worker, or use big
  actions (`module`).
- **mvnd started from inside actions is a stand-in.** It ran these benchmarks reliably, but one
  action of a larger build failed with a lost daemon connection. A production grid would run the warm
  Maven process as part of the worker (what Bazel calls a persistent worker).
- **The fresh checkout is 3.9s, not 1.5s as with Nx,** mostly because the client starts Maven once to
  export the graph (about 2s with `mvn`).
- **The real change re-runs everything** for the same reason as with Nx: every module depends on `m00`.

