# Performance

Measured with `mise run perf` (`perf/Bench.java`) on a generated reactor:
- 20 modules in a dependency chain;
- 820 classes, one test class and one jar per module;
- offline;
- median of 3 runs on a 4-core container;
- Maven 3.9 and mvnd 1.0.6 (which embeds Maven 3.9).

The same pass also covers the whole suite: all ITs pass through mvnd, and `ExternalBuildSystemIT` takes
6.9s through mvnd versus about 37s through mvn.

| Scenario | mvn | mvn + mojo-io | mvnd | mvnd + mojo-io |
|---|---|---|---|---|
| clean verify, empty cache (cold) | 15.7s | 16.7s (+6%) | 12.1s | 12.8s (+6%) |
| clean verify, cache off (state + strict only) | | 16.6s | | 11.4s |
| clean verify, warm cache | | **2.7s** (5.8×) | | **0.6s** (20×) |
| verify, nothing changed | 12.6s | **2.5s** | 9.6s | **0.6s** |
| verify, 1 source changed in the first module | 16.9s | **3.2s** | 10.6s | **0.7s** |
| graph export (dry run) | | 1.9s | | 0.2s |
| invocation floor (`-pl m19 -am validate`) | 1.2s | 1.3s | 0.1s | 0.1s |
| one task: `surefire:test` in m19 (`-pl -am -Dmojoio.only`) | | 2.4s | | **0.6s** |

Notes:
- **Cold overhead** is the price of hashing inputs, storing entries, recording state and the
  strict-mode write check: about 6%.
- **The first version cost +24% (mvn) and +30% (mvnd).** Two fixes brought it down:
  - Content hashes are memoized by path, size and modification time. A reactor otherwise re-hashes
    the same upstream jars for every downstream module and mojo, which grows with the square of the
    module count.
  - Each mojo's "after" snapshot is reused as the next mojo's "before".
- **"1 source changed"** appends a comment to one class in `m00`. The recompiled class is identical,
  so every downstream key is unchanged: only `m00`'s compile re-runs.

## Build systems: avoid JVM and Maven startup per task

A build system (Nx, Bazel, …) runs one Maven invocation per task. With plain `mvn`, every invocation
pays JVM start, Maven bootstrap and model building: the **invocation floor**, 1.2s here and more
on bigger reactors. Run tasks through **mvnd** instead:
- the floor drops to about 0.1s;
- a single task (`surefire:test` of the last module, against restored upstream outputs) takes 0.6s
  instead of 2.4s;
- the hash memo lives in the daemon, so warm builds also skip re-hashing unchanged files.

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
