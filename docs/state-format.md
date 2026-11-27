# Project-state format (`mojo-io-state 1`)

Mojos change the in-memory Maven project as well as files. For example:
- the compiler adds a generated-sources root and sets the artifact file;
- JaCoCo sets `argLine`.

Later mojos, and downstream modules, depend on those changes, but they're lost whenever a mojo
doesn't run in the same Maven process: when a build system runs a project's tasks one at a time, or
restores a task's outputs from its cache.

The extension therefore records each execution's changes in
`target/mojo-io/state/<artifactId>-<goal>-<executionId>.state`, next to the outputs. At the start of
each project's build, it replays the state files of executions that aren't going to run this time.
A build system that caches and restores a task's outputs together with its state files therefore
restores the state too, with nothing tool-specific involved.

## File format

UTF-8 text, one record per line, fields URL-encoded (`%` = empty string, `%00` = null):

```
mojo-io-state 1
execution org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent@default
change property argLine -javaagent%3A%40%40M2REPO%40%40%2F...%3Ddestfile%3D%40%40BASEDIR%40%40%2Ftarget%2Fjacoco.exec
```

| Line | Meaning |
|---|---|
| `execution <id>` | `groupId:artifactId:version:goal@executionId` |
| `phase <name>` | The lifecycle phase the execution is bound to, as a phase of the Maven 4 tree (`compile`, `test`, `package`, …). Replay uses it to leave out state beyond what a task needs. Absent for an execution bound to none. |
| `change compileSourceRoot <_> <path>` | Added a main source root. |
| `change testCompileSourceRoot <_> <path>` | Added a test source root. |
| `change property <name> <value>` | Set a project property. |
| `change propertyRemoved <name> <_>` | Removed a project property. |
| `change artifactFile <_> <path>` | Set the main artifact file. |
| `change attachedArtifact <type>:<classifier> <path>` | Attached an artifact (e.g. `test-jar:tests`, `java-source:sources`). |
| `change systemProperty <name> <value>` / `change systemPropertyRemoved <name> <_>` | Set or cleared a JVM system property (a hook handing data to later mojos). JVM/runtime properties are not recorded. |
| `change contextValue <name> <value>` | Set a text value in the project context (`MavenProject#setContextValue`). A name `@<class>#<key>` is a value in the context of the object of that class kept there: Eclipse Tycho's `ReactorProject`, through which its mojos hand values to later ones (the declarative services header). Other context values are not recorded. |
| `incomplete <reason>` | The execution made changes this format can't express (removed source roots, changed resources, replaced attachments). The extension doesn't replay it and logs why; a build system should not cache the task. |

Paths in values use the tokens `@@BASEDIR@@` (the module), `@@ROOT@@` (the multi-module root) and
`@@M2REPO@@` (the local repository), so a state file stays valid in another checkout or on another
machine. If a value refers to a local-repository file that's missing (for example the JaCoCo agent
jar), the state file isn't replayed, and the build log says which file is missing.

## Replay rules

- **When:** before a project's executions run, at most once per execution and build.
- **Own state:** the state of every recorded execution of the project that isn't in this build's
  plan for it and hasn't run in this build, through the last phase of the plan (all of it if a phase
  of the plan is unknown). Compiling doesn't replay what packaging recorded. Executions in the plan
  run and record their own state.
- **Upstream state with `mojoio.only`:** the projects that aren't selected run nothing. Their state is
  replayed when a selected project starts, and only **through the phase that project's mojos need them
  at**, by the same rules as the task graph's `dependsOn`:
  - compiling or testing needs a dependency through `ready` (its compiled classes);
  - packaging needs runtime dependencies through `package`;
  - a dependency on an attached artifact or an archive (a classifier, `test-jar`, `war`, …) needs
    `package`;
  - a plugin, or plugin dependency, built in the reactor needs `package`;
  - where code runs (runtime and test scopes), the runtime dependencies of dependencies come along.

  State beyond that isn't trusted: in a shared workspace it can be left over from an earlier build (an
  old jar next to freshly compiled classes), or be rewritten at that moment by a task running in
  parallel. A dependency no mojo of the plan needs isn't replayed; projects the selected one doesn't
  depend on (its parents) are replayed fully.
- **Order:** files are applied in file-name order. The changes of different executions are expected
  to be independent.
- **Maven 4's concurrent builder** calls the extension once per lifecycle step. An execution whose
  state was replayed in an earlier step and that runs in a later one keeps those changes in its
  recorded state if they still hold after it ran.
