# Arodnap

Arodnap is a build-backed repair tool for Java resource-leak warnings in supported
single-module Gradle repositories. It analyzes a temporary workspace copy of the
target project, keeps the original repository unchanged by default, and emits
machine-readable diagnostics, inference outputs, stage artifacts, and patch
bundles under `./arodnap-out`.

## What Arodnap Does

- runs the Checker Framework Resource Leak Checker against a supported Gradle
  project
- preserves inferred `.ajava` outputs when you ask for inference or repair
- applies Java-based repair stages against a workspace copy, not the original
  repository
- emits a normalized patch bundle that can be reviewed and applied later

## Supported Projects

Arodnap currently supports repositories that meet all of these conditions:

- the analysis root is the repository root
- the repository contains `build.gradle` or `build.gradle.kts`
- the repository is a standard single-module Gradle Java project
- main Java sources live under `src/main/java`
- the project builds with a conventional main compile target such as `classes`

## Unsupported Projects

Arodnap fails closed on unsupported shapes. Common unsupported cases are:

- Maven projects
- Ant projects
- multi-module Gradle builds
- custom main source-set layouts
- generated-source-heavy repositories
- workflows that expect Arodnap to mutate the original repository automatically

## Prerequisites

- Python 3.10 or newer
- Java 11 or newer
- a Gradle wrapper in the target repository, or `gradle` available on `PATH`
- GNU `patch`, exposed as `gpatch` or `patch`

Run Arodnap from a checkout of this repository:

```bash
python -m arodnap.main <command> [options] /path/to/repo
```

## Commands

| Command | Behavior |
| ------- | -------- |
| `analyze` | Run one build-backed RLC pass. No WPI loop. No repair stages. Emits diagnostics and adapter metadata. |
| `infer` | Run one `reanalyze(workspace)` cycle. Preserves inferred outputs, diagnostics, and adapter metadata. Does not run repair stages. |
| `repair` | Run the full workspace-based repair pipeline: analysis, close injection, owning-field handling, RLFixer, and patch materialization. |
| `apply` | Validate a previously emitted patch bundle with a dry run, then apply it to the original repository. This is the only public command that mutates the original repository. |

Common options:

- `--out-dir`: output root. Defaults to `./arodnap-out`.
- `--keep-workspace`: keep the temporary workspace after the command exits.
- `--build-args`: repeatable extra build arguments forwarded to the Gradle/WPI flow.
- `--compile-target`: override the compile target used for Gradle-backed analysis.
- `apply` also requires `--patch-dir`, usually `./arodnap-out/patches`.

Examples:

```bash
python -m arodnap.main analyze /path/to/repo
python -m arodnap.main infer /path/to/repo
python -m arodnap.main repair /path/to/repo
python -m arodnap.main apply --patch-dir ./arodnap-out/patches /path/to/repo
```

## Output Artifacts

By default Arodnap writes to `./arodnap-out`:

```text
arodnap-out/
  report.json
  manifest.json
  diagnostics/
  inference/
  logs/
  stages/
    close_injector/
    owning_field/
    rlfixer/
    rlpatcher/
  patches/
    manifest.json
```

Important outputs:

- `report.json`: top-level run summary
- `manifest.json`: top-level manifest with config, workspace, and adapter details
- `diagnostics/*.txt`: one diagnostics file per analysis point
- `logs/<label>/`: analysis logs and adapter-derived metadata files
- `inference/<label>/`: preserved inferred outputs for `infer` and `repair`
- `stages/<name>/stage_result.json`: structured stage results for `repair`
- `patches/manifest.json`: normalized patch bundle consumed by `apply`

Command-specific behavior:

- `analyze` produces diagnostics and metadata, but no populated inference output
  and no stage artifacts.
- `infer` produces one populated inference directory and no stage artifacts.
- `repair` produces stage-local logs, intermediate artifacts, and the final patch
  bundle.

## Patch / Apply Workflow

`repair` is intentionally non-destructive:

- Arodnap copies the target repository into a temporary workspace.
- Mutation-capable stages run only against that workspace copy.
- The final normalized patch bundle is emitted under `./arodnap-out/patches`.
- `apply` validates the bundle against a clean copy of the original repository
  before applying it.

That split lets you inspect `report.json`, diagnostics, inference output, and the
patch manifest before deciding whether to update the original repository.

## Troubleshooting

- If Arodnap says the project shape is unsupported, fix the repository layout or
  compile target rather than expecting best-effort guessing.
- If Gradle detection fails, provide a working `./gradlew` in the target
  repository or install `gradle` on `PATH`.
- If `repair` or `apply` fails with a patch prerequisite error, install GNU
  `patch` and expose it as `gpatch` or `patch`.
- If the default compile target is wrong for the repository, rerun with
  `--compile-target`.
