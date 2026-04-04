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

Common layers should depend on the adapter contract and registry instead of
hardwiring Gradle-specific path guesses.

## Stage Wrapper Lifecycle

Repair-stage contracts live under
[`arodnap/stages/`](/Users/sanjay/projects/arodnap/arodnap/stages).

Shared structure:

- [`base.py`](/Users/sanjay/projects/arodnap/arodnap/stages/base.py)
  defines the common wrapper lifecycle:
  `validate_inputs(...)`, `invoke_tool(...)`, `normalize_outputs(...)`,
  `validate_outputs(...)`, and `run(...)`.
- [`registry.py`](/Users/sanjay/projects/arodnap/arodnap/stages/registry.py)
  owns repair-stage ordering.

Wrapper rules in the current implementation:

- wrappers accept normalized Arodnap inputs only
- wrappers invoke subprocess work through the shared runtime layer
- wrappers normalize raw tool outputs before those outputs leave the stage
  boundary
- each stage writes `stage_result.json` plus stage-local artifacts and logs
- wrappers do not decide pipeline order
- wrappers do not rerun analysis directly

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

The regression tests in slice 14 lock in the deterministic parts of those
contracts.

## Legacy Boundary

The repository still keeps a legacy normalized regression path internally for
coverage. That path is not the preferred public usage story and should not be
used as the architecture source of truth for future work.

When extending Arodnap after v1.1, prefer:

- adapter-driven discovery over path guessing
- workspace-copy behavior over in-place mutation
- stage-local normalization over leaking raw tool outputs
- structured artifacts over ad hoc side effects
