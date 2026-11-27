# plugin-io.xml: declaring what a mojo reads and writes

`plugin-io.xml` describes the inputs and outputs of Maven mojos **in terms of their parameters**.
Because it's tied to parameters, one declaration holds for every configuration of the plugin. It is
the contract that lets a build system put a mojo execution into its task graph, hash it, run it in a
sandbox and cache its outputs ([task graph](task-graph.md)).

The format lives in its own dependency-free module (`mojo-io-spec`), with an XSD at
`dev/frontseat/mojoio/spec/plugin-io-1.0.xsd`. It's meant to be adopted as is by plugins, by
Maven core and by other tools. See the [Maven 4 proposal](maven4-proposal.md).

## Where declarations come from

The first source that mentions a goal wins outright:

1. **Project**: `.mvn/plugin-io/*.xml` at the multi-module root. Use it to fix or veto a declaration
   without waiting for a release, for example tests that talk to a live service.
2. **Plugin**: `META-INF/maven/plugin-io.xml` inside the plugin's own jar. Here `<plugin>` may omit
   its coordinates.
3. **Built-in**: `builtin-plugin-io.xml`, shipped with the extension, for common plugins that don't
   ship their own declarations yet.

A mojo nobody declares is not cacheable in the task graph. A mojo whose declarations use a kind the
reader doesn't know is not cacheable either.

## Example

```xml
<plugin-io xmlns="urn:mojo-io:plugin-io:1.0">
  <plugin groupId="org.apache.maven.plugins" artifactId="maven-compiler-plugin">
    <mojo goal="compile">
      <inputs>
        <files parameter="compileSourceRoots"/>
        <classpath parameter="compilePath"/>
      </inputs>
      <outputs>
        <directory parameter="outputDirectory" includes="**/*.class"/>
        <directory parameter="generatedSourcesDirectory"/>
      </outputs>
      <ignoredParameters>compileSourceRoots, compilePath, verbose, staleMillis</ignoredParameters>
    </mojo>
    <mojo goal="help" cacheable="false" reason="prints to the console only"/>
  </plugin>
</plugin-io>
```

## Semantics

The graph's **fingerprint** of a declared execution always covers the **effective configuration**:
every parameter, evaluated the way Maven configures the mojo (configured expression, else
`default-value`), with absolute paths made relative to the module, the multi-module root or the local
repository. So declarations only need to cover what that can't see:

| Element | Meaning |
|---|---|
| `<inputs>` | Things whose **content** matters. The graph lists them for the build system to hash. A parameter named here is normally also listed in `ignoredParameters`, so its path doesn't count in the fingerprint. |
| `<outputs>` | Files the mojo produces. They're declared rather than diffed, because a mojo that finds its outputs up to date writes nothing. |
| `<ignoredParameters>` | Parameters that can't change outputs: logging, process plumbing, and parameters already covered as inputs. They are left out of the fingerprint. Maven objects (`project`, `session`, `mojoExecution`, …) are always ignored. |
| `<ignoredWrites>` | Ant patterns, relative to the project build directory, for files the mojo writes that are bookkeeping rather than outputs (the compiler's `maven-status/**`, the jar plugin's `maven-archiver/**`). The build system leaves them out of observed outputs and of its check that a task wrote only its declared outputs. |
| `cacheable="false" reason="…"` | The mojo has effects that can't be captured: system properties, static state, the network, files outside the project. A reason is required. |

### Mojos that fork other goals

Some mojos run another goal first, inside their own run: `pmd:check` forks `pmd:pmd`, `spotbugs:check`
forks `spotbugs:spotbugs`. Such a mojo is one unit with its forked executions in the task graph:
- declare each forked goal too (its inputs and outputs);
- their configuration joins the forking mojo's fingerprint, their inputs and outputs are its inputs
  and outputs.

If a forked execution has no declaration (for example a forked lifecycle with undeclared mojos), the
forking mojo isn't cacheable.

### Files a build extension reads while projects load

A plugin used as a build extension (`<extensions>true</extensions>`) can read project files before any
mojo runs. Eclipse Tycho reads manifests, `feature.xml` and target definitions there. Name them, as
Ant patterns relative to the project directory, in a `projectLoad` element of the plugin:

```xml
<plugin groupId="org.eclipse.tycho" artifactId="tycho-maven-plugin">
  <projectLoad>META-INF/MANIFEST.MF, build.properties, feature.xml, *.target</projectLoad>
</plugin>
```

The task graph lists the matching files of each project whose build has the plugin as `loadFiles`,
for build systems that run tasks in a sandbox (docs/task-graph.md). Only project and built-in
declarations are read for this, not declarations inside plugin jars.

### Checking a declaration

A missing **input** means stale cache hits, a missing **output** an incomplete restore. For every new
declaration:
1. Export the task graph (`mvn verify -Dmojoio.graph.output=graph.json`) and read the execution's
   `inputs` and `outputs`. An input whose paths don't exist is a gap: the parameter is probably empty
   by default, and the mojo falls back to something else (declare that with `expression=`).
2. Build with the build system, then change each kind of input (a source file, each configuration
   file) and check that the task runs again. A build system that runs each task in a sandbox and
   checks its writes against the declared outputs finds missing outputs
   ([task graph](task-graph.md#caching-maven-tasks-in-a-build-system)).

[docs/real-projects.md](real-projects.md) has a worked example, including a first attempt that was wrong.

### Input kinds

| Kind | Attributes | Covers |
|---|---|---|
| `files` | `parameter` or `expression`, `excludes?` | Content of the files/trees a path or path-list parameter names, or that a Maven expression such as `${project.file}` evaluates to. `excludes`: Ant patterns, relative to each path, of files left out, such as what the mojo itself writes among the files it reads. |
| `classpath` | `parameter` | A classpath parameter, described in the graph by the dependency scope the mojo resolves. The build system covers it through the task's `dependsOn` and the project's `externalDependencies`. |
| `testClasspath` | – | The project's test classpath, for mojos that build it internally or read dependencies some other way. Described like `classpath`, with scope `test`. |
| `resources` | `parameter` or `expression` | A `List<Resource>`: settings plus selected files. With filtering, also project coordinates, project properties and user properties. |
| `projectFiles` | `excludes?` | The project's own files: its directory without the build directory, nested modules and VCS data. For mojos that scan the project (license or format checks). |
| `projectModel` | – | The project's effective model (pom with parents, profiles and properties applied). For mojos that read the project rather than their own configuration (rule checks). |
| `reactorModels` | – | The effective models of every project of the build. For aggregator mojos (one execution that reads the whole reactor). An aggregator is only cacheable when its declaration has this input. |
| `sessionGoals` | – | The goals and phases the build was started with. For mojos whose result depends on them (a check of the build plan). |
| `mavenVersion` | – | The Maven version, for mojos whose result depends on it. |
| `projectProperty` | `nameParameter?`, `defaultName` | The current value of a project property the mojo reads and rewrites. |

### Output kinds

| Kind | Attributes | Collects |
|---|---|---|
| `directory` | `parameter` or `expression`, `includes?` (default `**`) | Files under a directory (a parameter, or a Maven expression such as `${project.build.outputDirectory}`) matching Ant patterns. |
| `observed` | `includes?` (default `**`), `excludes?` | Whatever the mojo writes in the build directory while it runs (patterns relative to it), known only after the run. For mojos that run code with hooks of its own: tests, agents (JaCoCo), forked tools. Flagged `"observed": true` in the graph: the build system takes what the execution wrote there. |
| `file` | `parameter`, `name` | One file in a directory parameter. `name` is a template: `{param}` is a parameter's value, `{-param}` is `-value` when not empty, e.g. `{finalName}{-classifier}.jar`. |
| `copiedResources` | `resourcesParameter`, `outputDirectoryParameter` | The files a resources mojo copies. |
| `javaagentDestFiles` | `parameter` | `destfile=` targets of `-javaagent:` options in a command-line parameter (coverage data). |

`files`, `classpath` and `directory` are the **core** kinds. Any well-designed mojo can be described
with them alone. The others are **derived** kinds: they exist because today's plugins compute
those values internally instead of exposing them as parameters.

### Project state

Restoring files isn't enough: mojos also change the in-memory project. For example, the compiler
adds a generated-sources root and JaCoCo sets `argLine`. The format doesn't declare these. Instead
the extension records them in state files (by diffing the project, see [state format](state-format.md)). The
[proposal](maven4-proposal.md) shows how Maven 4's `ProjectManager` makes this exact.

## For plugin authors

Add `src/main/resources/META-INF/maven/plugin-io.xml` to the plugin, with no coordinates on `<plugin>`.
Keep declarations next to the code so they change together. Validate the file against the XSD in your
build.
