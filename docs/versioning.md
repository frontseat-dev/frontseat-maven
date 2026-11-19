# Versioning and stamping

Version strings end up inside outputs:
- `pom.properties`, the embedded `pom.xml` and `MANIFEST.MF` inside jars;
- filtered resources (`version=${project.version}`);
- file names (`lib-core-1.0.0.jar`);
- mojo-io's own state files (the recorded artifact file).

A cache must not serve the old version's metadata after a bump. It also shouldn't recompile the
world just because a version changed, since that's most CI builds in a monorepo with per-build or
per-release versions.

mojo-io handles both with **stamping**: outputs are reused, and the version is rewritten into them on
restore, wherever that's provably safe.

## Stamp values

- `project.version` of the module, always.
- Any properties listed in `-Dmojoio.stamp.properties=buildNumber,git.commit.id`, looked up in user
  properties first, then project properties.

Values shorter than 5 characters are ignored, so a version like `1.0` never stamps unrelated text.

## Two kinds of mojo

Each mojo is declared as one or the other with `stamping` in [`plugin-io.xml`](plugin-io.md):

| `stamping` | Meaning | Cache key | On restore |
|---|---|---|---|
| `exact` (default) | The mojo may *act* on stamp values. A test can assert the version; a plugin can branch on it. | Stamp values are part of the key, so a bump re-runs it. | Restored as stored. |
| `carry` | The mojo only *copies* stamp values into outputs: compilers, resource filtering, archivers. | Stamp values are replaced by `@@STAMP:<name>@@` tokens in configuration, input values, text-file content and text entries of archives. A bump keeps the key. | Every occurrence of the stored values is rewritten to the current ones. |

Built-in declarations: resources, compiler and jar are `carry`; surefire and JaCoCo are `exact`.

## What gets rewritten

Each output of a `carry` execution is classified when it's stored:

| Mode | When | Restore |
|---|---|---|
| `RAW` | Contains no stamp value. | Byte for byte. |
| `TEXT` | A text file containing stamp values. | Values replaced. |
| `ARCHIVE` | A jar/zip whose stamp values are all in text entries (`pom.properties`, `MANIFEST.MF`, embedded pom, filtered resources). | Those entries are rewritten. Other entries are copied unchanged, and entry order and timestamps are kept. |
| `UNSTAMPABLE` | A stamp value sits where it can't be rewritten safely, e.g. a version constant compiled into a `.class` file. | See below. |

Output **paths** and **state records** are tokenized too. `target/lib-core-@@STAMP:project.version@@.jar`
and the jar plugin's `artifactFile` record therefore come back with the current version.
Local-repository paths are never tokenized: their versions are dependency versions, not stamps.

## The safety net: pinned entries

If any output of a `carry` execution is `UNSTAMPABLE`:
1. The stamp-free key gets a **redirect** entry.
2. The real entry is stored under a key **pinned** to the exact stamp values.

After a bump, the lookup follows the redirect to a pinned key that doesn't exist, so the execution
runs again. A class compiled from a version-templated source is therefore never restored with the
old constant. With unchanged stamps, the pinned entry hits as usual (see `VersioningIT`).

## Limits

- Detection is by exact value. A stamp value that also appears in a text output for unrelated
  reasons is rewritten too. The minimum length makes this unlikely, and versions are distinctive.
- Only the stamp values listed above are known. A timestamp or commit id embedded in outputs, but
  not listed in `mojoio.stamp.properties`, is just content: it makes the execution miss whenever it
  changes, which is correct but not reusable.
- `exact` mojos can't be told apart automatically from `carry` ones. The declaration decides, and
  `exact` is the safe default.
