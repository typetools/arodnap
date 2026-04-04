# Arodnap v1.1 Architecture

This note describes the implemented v1.1 architecture. It is intentionally
limited to the current supported scope: single-module Gradle repositories at
the repo root.

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
[`arodnap/orchestrator/`](/Users/sanjay/projects/arodnap/arodnap/orchestrator).

## Runtime Layer

The runtime layer lives under
[`arodnap/runtime/`](/Users/sanjay/projects/arodnap/arodnap/runtime).

Current v1.1 responsibilities:

- shared subprocess execution through `run_command(...)`
- shared `CommandResult` and `CommandExecutionError`
- shared command-log rendering for analysis, stages, and patch execution
- small environment overlay support via `environment_with_overrides(...)`

The runtime layer is intentionally narrow. It centralizes the behavior Arodnap
actually reuses today instead of introducing a larger framework for future
tools.

## Adapter Model

The build-adapter boundary lives under
[`arodnap/build_adapters/`](/Users/sanjay/projects/arodnap/arodnap/build_adapters).

Key pieces:

- [`base.py`](/Users/sanjay/projects/arodnap/arodnap/build_adapters/base.py)
  defines the `BuildAdapterContract`, `ProjectModel`, `AdapterMetadata`, and
  adapter error types.
- [`registry.py`](/Users/sanjay/projects/arodnap/arodnap/build_adapters/registry.py)
  owns adapter registration and selection.
- [`gradle.py`](/Users/sanjay/projects/arodnap/arodnap/build_adapters/gradle.py)
  is the only registered backend in v1.1.

Current adapter responsibilities:

- detect whether a repo matches the supported build shape
- inspect the repo and construct a `ProjectModel`
- validate the compile target
- emit source-file, app-class, classpath-entry, and adapter-metadata files

**Registry-first invariant (v1.1):** All adapter selection must route through
`select_build_adapter(...)` in `build_adapters/registry.py`. No orchestration
layer may construct or import a concrete adapter class directly. Adding a new
build-system backend in v2 means registering a new `RegisteredBuildAdapter`
entry — not adding a new import in the orchestrator.

v1.1 registers one backend: `GradleAdapter` (`gradle-v1`). `doctor`,
`analyze`, `infer`, `repair`, and `reanalyze` all select adapters through the
registry. `_initial_state()` in the pipeline falls back to
`default_build_tool_selection()` only on `UnsupportedProjectError` during
early state initialization; that fallback is intentional v1.1-scoped behavior
that will be revisited when multi-adapter support is introduced in v2.

## Stage Wrapper Lifecycle

Repair-stage contracts live under
[`arodnap/stages/`](/Users/sanjay/projects/arodnap/arodnap/stages).

Shared structure:

- [`base.py`](/Users/sanjay/projects/arodnap/arodnap/stages/base.py)
  defines the common wrapper lifecycle:
  `validate_inputs(...)`, `invoke_tool(...)`, `normalize_outputs(...)`,
  `validate_outputs(...)`, and `run(...)`.
- [`registry.py`](/Users/sanjay/projects/arodnap/arodnap/stages/registry.py)
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
  `rlpatcher`)

The current repair order remains:

1. `close_injector`
2. `owning_field`
3. `rlfixer`
4. `rlpatcher`

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
- `report.json` is the run-level summary view
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

Stable `name` values in v1.1: `python_runtime`, `java_runtime`,
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

The repository still keeps a legacy normalized regression path internally for
coverage. That path is not the preferred public usage story and should not be
used as the architecture source of truth for future work.

When extending Arodnap after v1.1, prefer:

- adapter-driven discovery over path guessing
- workspace-copy behavior over in-place mutation
- stage-local normalization over leaking raw tool outputs
- structured artifacts over ad hoc side effects
