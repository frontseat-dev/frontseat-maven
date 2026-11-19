# Project-state format (`mojo-io-state 1`)

Mojos change the in-memory Maven project as well as files. For example:
- the compiler adds a generated-sources root and sets the artifact file;
- JaCoCo sets `argLine`.

Later mojos, and downstream modules, depend on those changes, but they're lost whenever a mojo
doesn't run because a cache restored its outputs.

The extension therefore records each execution's changes in
`target/mojo-io/state/<artifactId>-<goal>-<executionId>.state`, next to the outputs. At the start of
each project's build, it replays the state files of executions that aren't going to run this time.
Any tool that restores `target/` therefore restores the state too, with nothing tool-specific
involved.

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
| `change compileSourceRoot <_> <path>` | Added a main source root. |
| `change testCompileSourceRoot <_> <path>` | Added a test source root. |
| `change property <name> <value>` | Set a project property. |
| `change propertyRemoved <name> <_>` | Removed a project property. |
| `change artifactFile <_> <path>` | Set the main artifact file. |
| `incomplete <reason>` | The execution made changes this format can't express (removed source roots, changed resources, attached artifacts). Don't replay it; treat the execution as not restorable. |

Paths in values use the tokens `@@BASEDIR@@` (the module), `@@ROOT@@` (the multi-module root) and
`@@M2REPO@@` (the local repository), so a state file stays valid in another checkout or on another
machine. Stamp values such as the project version appear as `@@STAMP:<name>@@` (e.g. the jar plugin's
`artifactFile`), so the state follows a version bump too ([versioning](versioning.md)). If a value refers to a local-repository file that's missing (for example the JaCoCo agent
jar), the record isn't replayed.

## Replay rules

- **When:** at most once per project per build, before its first execution.
- **What:** the state of every recorded execution that isn't in this build's plan for the project.
  Executions in the plan run (or are restored by the built-in cache) and set their own state.
- **Order:** files are applied in file-name order. The changes of different executions are expected
  to be independent.
