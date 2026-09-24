# Arodnap Architecture

This note describes the implemented architecture: a Python orchestrator that
captures a project's own build, and the paper's Java analysis and repair tools.

## Public Flow

The public commands are:

```text
arodnap analyze
arodnap infer
arodnap repair
arodnap apply
arodnap doctor
```

Behavioral boundaries:

- `analyze` and `infer` are analysis-only flows.
- `repair` runs the ordered repair-stage pipeline against a workspace copy and
  emits a normalized patch bundle.
- `apply` is the only command that mutates the original repository.
- `doctor` is diagnostic-only and does not mutate the target repo.

## Workspace Model

The orchestrator copies the target repository into a temporary workspace for
`analyze`, `infer`, `repair`, and `doctor` validation steps that need a build.
That gives Arodnap a fail-closed default:

- discover build facts from the adapter
- normalize stage-local outputs
- verify artifacts before reporting success
- keep the original repo untouched until `apply`

The relevant orchestration lives under
[`arodnap/orchestrator/`](../arodnap/orchestrator).

## Runtime Layer

The runtime layer lives under
[`arodnap/runtime/`](../arodnap/runtime).

Current v1.1 responsibilities:

- shared subprocess execution through `run_command(...)`
- shared `CommandResult` and `CommandExecutionError`
- optional per-command time limits (`--build-timeout`, `--analysis-timeout`,
  `--stage-timeout`; none by default): a command over its limit is killed with
  its process group and raises `CommandTimeoutError`
- shared command-log rendering for analysis, stages, and patch execution
- small environment overlay support via `environment_with_overrides(...)`
- JDK resolution via `resolve_jdk()`: `JAVA_HOME`, else the `java` on `PATH`

The Checker Framework distribution comes from `--checker-framework`, then
`$ARODNAP_CHECKER_FRAMEWORK`, then the bundled
`checker_framework/checker-framework-4.2.3` (a trimmed release; see its
`VENDORED.md`). Arodnap runs it as `<JDK>/bin/java -jar checker/dist/checker.jar`
on the resolved JDK, which must be at least the distribution's own minimum (read
from `checker.jar`) and RLFixer's 17 (`analysis/checker_framework.py`).

Whole-program inference does not use the distribution's `wpi.sh`.
`analysis/wpi_runner.py` runs the same fixpoint loop as do-like-javac's WPI tool
(`-Ainfer=ajava -Awarns`, each iteration reading the previous one via `-Aajava`)
directly on the adapter's source list and classpath, and keeps only the final
iteration. `tests/test_wpi_upstream_parity.py` pins the upstream loop it
mirrors, so a Checker Framework upgrade that changes it fails until reviewed.
The final Resource Leak Checker pass uses the same flags as the paper's runs,
including `-Astubs=checker_framework/stubs` and `-Xmaxwarns 10000`.

The runtime layer is intentionally narrow. It centralizes the behavior Arodnap
actually reuses today instead of introducing a larger framework for future
tools.

## Adapter Model

The build-adapter boundary lives under
[`arodnap/build_adapters/`](../arodnap/build_adapters).

Adapters learn a project's compilation by running its own build once per
analysis point with a recording hook installed through the build tool's
official extension point ([`captured.py`](../arodnap/build_adapters/captured.py)):

- `gradle`: an init script records the inputs of every `JavaCompile` task
- `maven`: the compiler plugin forks to a recording `javac` shim
- `ant`: a recording compiler adapter (`restructure_plugins/AntCapture`)
- `command`: the recording `javac` shim first on `PATH`, for any
  `-- <build command>`

Every hook writes the same JSON lines (`{cwd, args}`), which
[`capture.py`](../arodnap/build_adapters/capture.py) parses into compile
units and merges into one analysis universe: all units' sources plus
generated sources, the union of classpaths minus artifacts the capture build
wrote, the highest release level, and an analysis root for RLFixer. The
adapter then compiles the merged sources itself into
`<workspace>/.arodnap/analysis-classes`, so app classes and RLFixer's
classpath do not depend on the build's output layout.

The adapter contract (`inspect`, `validate_compile`, `write_*_file`) is
unchanged, so `analyze`, `reanalyze`, `doctor` and the stages are
build-system agnostic.

**Registry-first invariant:** adapter selection goes through
`select_build_adapter(...)` in `build_adapters/registry.py`. With a build
command, its executable name picks the adapter (`gradle`/`gradlew`,
`mvn`/`mvnw`, `ant`, otherwise `command`); without one, the first registered
adapter whose build file exists is used. Adding a build system means adding a
`RegisteredBuildAdapter` entry.

## Stage Wrapper Lifecycle

Repair-stage contracts live under
[`arodnap/stages/`](../arodnap/stages).

Shared structure:

- [`base.py`](../arodnap/stages/base.py)
  defines the common wrapper lifecycle:
  `validate_inputs(...)`, `invoke_tool(...)`, `normalize_outputs(...)`,
  `validate_outputs(...)`, and `run(...)`.
- [`registry.py`](../arodnap/stages/registry.py)
  owns repair-stage ordering and per-stage post-run behavior flags.

Wrapper rules in the current implementation:

- wrappers accept normalized Arodnap inputs only
- wrappers invoke subprocess work through the shared runtime layer
- wrappers normalize raw tool outputs before those outputs leave the stage
  boundary
- each stage writes `stage_result.json` plus stage-local artifacts and logs
- wrappers do not decide pipeline order
- wrappers do not rerun analysis directly

**Registry-first invariant (v1.1):** Repair-stage order and per-stage
pipeline behavior are declared in `REPAIR_STAGE_REGISTRY` in
`stages/registry.py`. The pipeline loop in `orchestrator/pipeline.py`
consumes only the registry — it does not contain stage-name string
comparisons or import concrete stage modules directly. Adding a new repair
stage in v2 means adding a `RepairStageDefinition` entry to the registry with
its runner callable and any required flags.

Each `RepairStageDefinition` declares:

- `runner`: the callable that executes the stage given a `StageRunInput`
- `rerun_analysis_label`: non-`None` for stages that may require a
  `reanalyze(workspace)` pass after they mutate sources
- `captures_rlfixer_result`: `True` for the stage whose result is forwarded
  as `rlfixer_result` to subsequent stages (currently `rlfixer`)
- `promotes_patch_manifest`: `True` for the stage whose `patch_manifest`
  artifact is promoted to the top-level `patches/manifest.json` (currently
  `bundle`); promotion copies the patch files next to the manifest

The current repair order is:

1. `close_injector` (rerun analysis if it changed sources)
2. `owning_field` (rerun analysis if it changed sources)
3. `rlfixer`: runs the RLFixer jar on build-discovered inputs and fails if
   RLFixer crashes or prints no fixes report
4. `rlpatcher`: materializes RLFixer suggestions and applies them to the
   workspace, skipping any that conflict with an earlier patch (rerun analysis
   as `final` if it changed sources). Its `patch_manifest.json` records one
   outcome per suggestion under `fixes`: `materialized`, `no_change` (RLFixer
   or RLPatcher found nothing to change), `rejected` (failed RLPatcher's
   compile check), `unsafe` (the close could only be placed by moving the
   allocation ahead of code that runs before it, which would change behavior),
   `unsupported`, `crashed` or `timed_out` (ran longer than `--stage-timeout`)
5. `bundle`: diffs the original repository against the final workspace into
   one patch and verifies it by replaying it onto a clean copy

The Java stage tools receive the build's source list and classpath through
`-Darodnap.sourcesFile` and `-Darodnap.classpathFile`, and write raw patches
to `-Darodnap.patchFile` inside the stage directory. They run on the JDK
resolved by `arodnap/runtime/jdk.py` (`JAVA_HOME`, else `java` on `PATH`).

## Outputs

Top-level structured outputs are written under `arodnap-out/`, with the exact
top-level JSON files depending on the command:

- `manifest.json`
- `report.json`
- `doctor.json`
- `diagnostics/`
- `inference/`
- `logs/`
- `stages/`
- `patches/`

Important contracts:

- `manifest.json` is the run-level machine-readable ledger
- `report.json` is the run-level summary view; after `repair` its `leaks` section
  lists every `required.method.not.called` warning with `status` (`fixed` or
  `remaining`), `fixed_by` (the stage after which it disappeared), `reason` (a
  stable code; `leaks.reasons` maps codes to text) and `first_seen` (the analysis
  run that first reported it). Warnings are followed across analysis runs by file,
  checker key and the `-Adetailedmsgtext` fields, since patches shift lines
  (`arodnap/reporting/leaks.py`). `report.html` renders the same data.
- `stages/<stage>/stage_result.json` is the stable stage result boundary
- `patches/manifest.json` is the normalized patch bundle consumed by `apply`

The regression tests in `tests/test_structured_output_regressions.py` lock in
the deterministic parts of those contracts.

### Minimum Stable v1.1 Output Contracts

These fields are the stable v1.1 machine-readable contract. v2 work must
either preserve all of them unchanged or explicitly update this section and
`tests/test_structured_output_regressions.py` before landing breaking changes.

#### `manifest.json` (written by `analyze`, `infer`, `repair`)

Minimum required top-level keys:

| Key | Type | Notes |
|-----|------|-------|
| `success` | bool | `true` on a clean run |
| `error` | string \| null | error message if `success` is `false` |
| `error_type` | string \| null | exception class name if `success` is `false` |
| `build_system` | string | e.g. `"gradle"` |
| `adapter_name` | string | e.g. `"gradle-v1"` |
| `legacy_regression_enabled` | bool | always `false` in public flows |
| `config` | object | includes `command`, `workspace_mode`, `keep_workspace`, `build_args`, `compile_target` |
| `workspace_root` | string | absolute path to the temporary workspace used |
| `current_analysis` | object \| null | `ReanalyzeResult` fields; `null` if analysis never completed |
| `stage_history` | array | one `StageResult` object per executed stage |
| `artifacts` | object | paths to `diagnostics_dir`, `inference_dir`, `logs_dir`, `stages_dir`, `patch_bundle_dir`, `report`, `manifest`, `patches_manifest` |
| `run_metadata` | object | includes `tool_version`, `command`, `repo_root`, `workspace_root`, `adapter_name`, `java_version`, `started_at`, `completed_at`, `elapsed_seconds` |
| `adapter` | object | includes `build_system`, `adapter_name`, `compile_target`, `source_root`, `compiled_classes_root` |
| `analysis_runs` | array | one entry per `reanalyze` call with `label`, `success`, `warning_count`, timing |
| `stage_timings` | array | one entry per stage with `stage`, `success`, `changed`, `elapsed_seconds` |
| `stage_execution_summary` | object | includes `executed`, `changed`, `reruns_requested`, `successful`, `failed_attempts` |

#### `report.json` (written by `analyze`, `infer`, `repair`)

Minimum required top-level keys:

| Key | Type | Notes |
|-----|------|-------|
| `success` | bool | |
| `error` | string \| null | |
| `error_type` | string \| null | |
| `repo_root` | string | |
| `workspace_root` | string | |
| `final_analysis` | object \| null | same shape as `manifest.json` `current_analysis` |
| `diagnostics` | object | includes `final_diagnostics_path`, `final_warning_count` |
| `executed_stages` | array | one object per stage with `stage`, `changed`, `rerun_required`, `success`, `artifacts` |
| `artifacts` | object | same keys as `manifest.json` `artifacts` |
| `run_metadata` | object | same keys as `manifest.json` `run_metadata` |
| `adapter` | object | same keys as `manifest.json` `adapter` |
| `analysis_runs` | array | same shape as `manifest.json` `analysis_runs` |
| `stage_timings` | array | same shape as `manifest.json` `stage_timings` |
| `stage_execution_summary` | object | same shape as `manifest.json` `stage_execution_summary` |

#### `doctor.json` (written by `doctor`)

Minimum required top-level keys:

| Key | Type | Notes |
|-----|------|-------|
| `success` | bool | `true` when no check has status `"error"` |
| `repo_root` | string | |
| `out_dir` | string | |
| `build_args` | array | |
| `compile_target` | string \| null | |
| `checks` | array | one object per check |

Each check object minimum keys:

| Key | Type | Notes |
|-----|------|-------|
| `name` | string | stable check identifier, e.g. `"java_runtime"` |
| `status` | string | one of `"ok"`, `"warning"`, `"error"` |
| `message` | string | human-readable summary |
| `details` | object \| null | optional structured detail |

Stable `name` values: `python_runtime`, `java_runtime`,
`patch_binary`, `checker_framework_path`, `checker_framework_tools`,
`plugin_jars`, `repo_path`, `adapter_selection`, `repo_support`,
`source_root`, `compile_target`.

#### `stages/<stage>/stage_result.json` (written by each repair stage)

Minimum required keys (matches `StageResult` dataclass in `arodnap/contracts.py`):

| Key | Type | Notes |
|-----|------|-------|
| `stage` | string | stage name, e.g. `"close_injector"` |
| `changed` | bool | `true` if the stage mutated workspace sources |
| `changed_files` | array of strings | repo-root-relative paths of changed files |
| `rerun_required` | bool | `true` if `reanalyze(workspace)` must follow this stage |
| `artifacts` | object | string → path map of stage-local artifacts; always includes `"log"` |
| `notes` | array of strings | human-readable stage notes |
| `success` | bool | `true` if the stage completed without error |

### v2 Output Contract Gate

Before any v2 work changes the structure or semantics of the outputs above:

1. Update this section to reflect the intended new contract.
2. Update `tests/test_structured_output_regressions.py` to match.
3. If a field is removed or renamed, record it as a breaking change in the
   relevant plan doc before landing the code change.

Additive changes (new optional fields) do not require a gate update.

## Legacy Boundary

The paper's original normalized-layout pipeline lives in `legacy/` for
reference. Nothing in `arodnap/` uses it, and it should not be used as the
architecture source of truth for future work.

When extending Arodnap after v1.1, prefer:

- adapter-driven discovery over path guessing
- workspace-copy behavior over in-place mutation
- stage-local normalization over leaking raw tool outputs
- structured artifacts over ad hoc side effects
