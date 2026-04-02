# `plans/by_checklist.md` Roadmap

## Summary

Create a single numbered checklist that turns the existing `v1.md`, `v2.md`, and `v3.md` roadmaps into implementation slices that can be handed to Codex one at a time.

This checklist:

- uses `plans/by_checklist.md`, not `plan/by_checklist.md`, because the repo already uses `plans/`
- assigns every step a stable number
- keeps each step narrow enough to implement and verify in one Codex session
- includes an explicit done condition for each step
- preserves the current roadmap decisions: no field enhancement in v1, legacy normalized flow stays internal, original repo not mutated by default

## V1 Checklist

- [x] **1.1** Create the new Python package skeleton and core dataclasses (`RunConfig`, `PipelineState`, `ReanalyzeResult`, `StageResult`); done when imports work and unit tests cover object creation and serialization.
- [x] **1.2** Implement the workspace copy and cleanup model; done when a test proves the original repo is untouched and the temp workspace lifecycle is correct.
- [x] **1.3** Add CLI command scaffolding for `analyze`, `infer`, `repair`, and `apply`; done when command parsing works and each command reaches a stub orchestrator entrypoint.
- [x] **1.4** Implement Gradle project detection and compile validation for the supported single-module shape; done when supported and unsupported fixture repos are classified correctly.
- [x] **1.5** Implement Gradle source-file extraction for `src/main/java`; done when the adapter writes the expected source file list for a fixture repo.
- [x] **1.6** Implement Gradle app-class extraction from compiled outputs; done when the adapter writes the expected class list including nested classes and excluding `module-info.class`.
- [x] **1.7** Implement Gradle classpath extraction and adapter metadata emission using a transient init script or equivalent Gradle-backed mechanism; done when the adapter writes a reproducible classpath file and machine-readable metadata.
- [x] **1.8** Implement the official Checker Framework `wpi.sh` runner against the workspace; done when logs and stabilized inference outputs are emitted under the new output layout.
- [x] **1.9** Implement the build-backed RLC runner and `reanalyze(workspace)`; done when one call returns diagnostics, inference dir, and adapter-derived metadata with no direct legacy path assumptions.
- [x] **1.10** Implement the close-injector stage wrapper with structured `StageResult`; done when raw tool outputs are normalized and patch paths no longer leak `src/` assumptions outside the wrapper.
- [x] **1.11** Implement the owning-field stage wrapper with structured `StageResult`; done when it matches the close-injector contract and rerun decisions are driven only by stage results.
- [x] **1.12** Implement RLFixer compatibility bundle generation (`info/classes`, `info/sources`, compatibility jar); done when the bundle is produced from the final workspace state and stored under stage-local artifacts.
- [x] **1.13** Implement the RLFixer stage wrapper using the compatibility bundle; done when fixes and debug outputs are emitted without direct normalized-layout logic in the orchestrator.
- [x] **1.14** Implement the RLPatcher stage wrapper plus patch manifest generation; done when emitted patches are normalized to repo-root-relative artifacts and recorded with strip level, changed files, and preimage hashes.
- [x] **1.15** Implement `apply` plus patch dry-run validation against a clean copy of the original repo; done when invalid patch bundles fail closed and valid bundles apply sequentially.
- [x] **1.16** Implement `report.json`, `manifest.json`, stage-local artifact layout, and top-level output layout; done when one integration run emits the documented structure under `./arodnap-out`.
- [x] **1.17** Add integration fixtures and regression coverage, then update the public README to the new CLI; done when the fixture matrix covers plain analysis, close injection, owning-field handling, and one internal legacy regression path.
- [x] **1.18** Harden patch-tool compatibility across Linux and macOS; done when Arodnap prefers GNU `patch` when available, supports `gpatch`-style installations on macOS, records the selected patch binary and version in logs, and fails with a clear prerequisite error when only an incompatible patch implementation is available.

## V2 Checklist

- [ ] **2.1** Introduce the shared multi-adapter project model; done when Gradle-backed v1 still works through the same abstraction.
- [ ] **2.2** Add `arodnap.yaml` parsing and validation with explicit precedence rules; done when config-backed overrides work and ambiguous detection fails clearly.
- [ ] **2.3** Implement the single-module Maven adapter; done when the same `reanalyze(workspace)` contract works on a Maven fixture repo.
- [ ] **2.4** Implement the Ant adapter with explicit compile-target support; done when an Ant fixture repo can run through the adapter contract.
- [ ] **2.5** Add checkpointing and resumable execution; done when a stopped run can resume from machine-readable checkpoint data without recomputing completed stages.
- [ ] **2.6** Add structured error taxonomy and per-stage failure reporting; done when failures produce categorized machine-readable summaries with command, exit status, and log path.
- [ ] **2.7** Add patch preview mode, per-stage bundles, and combined bundle support; done when the patch manifest can represent preview and apply flows without ambiguity.
- [ ] **2.8** Add multi-module Gradle selected-module analysis; done when one chosen leaf module can be analyzed while dependent module outputs contribute to classpath resolution.
- [ ] **2.9** Add multi-module Maven selected-module analysis; done when the Maven path mirrors the Gradle selected-module contract.
- [ ] **2.10** Expand CI and fixtures across Gradle, Maven, Ant, and multi-module shapes; done when the fixture matrix validates adapters, resume behavior, and structured outputs.

## V3 Checklist

- [ ] **3.1** Publish a compatibility matrix covering runtimes, build tools, and major dependencies; done when the supported environment matrix is explicit in docs.
- [ ] **3.2** Split Python runtime and development dependencies and add reproducible installation flows; done when release and dev installs are documented and tested.
- [ ] **3.3** Version Java stage artifacts and align them with the Python package release story; done when releases can identify exact tool versions used.
- [ ] **3.4** Add structured changelog and migration-note policy; done when the repo has a release-ready changelog format and migration template.
- [ ] **3.5** Modernize JavaParser with fixture-backed validation; done when parser-sensitive behavior is revalidated on representative repos.
- [ ] **3.6** Modernize WALA with fixture-backed validation; done when RLFixer behavior is revalidated after the upgrade.
- [ ] **3.7** Modernize Checker Framework integration and define the supported CF release line; done when WPI behavior and support policy are documented and tested.
- [ ] **3.8** Add `arodnap doctor` and release-quality diagnostics; done when environment validation can be run without starting analysis.
- [ ] **3.9** Publish install docs, quickstart, troubleshooting, architecture guide, and release checklist; done when an external user can install and understand the tool from docs alone.
- [ ] **3.10** Add release automation and fixture-backed release confidence checks; done when tagged releases can produce traceable artifacts with validated fixture runs.

## Test Plan

- Every checklist item must be implementable and verifiable in one bounded Codex request.
- No step may assume the previous one “mostly worked”; each step’s done condition must be explicit.
- V1 steps must preserve the rules already locked in `plans/v1.md`: no field enhancement, no default mutation of the original repo, `reanalyze(workspace)` as the only analysis rerun primitive.
- Steps that emit patches must include dry-run validation before success can be claimed.
- Any step that reveals a roadmap gap must update the relevant version plan before the next implementation step starts.

## Assumptions

- The checklist file path is `plans/by_checklist.md`.
- The checklist is an execution companion to `plans/v1.md`, `plans/v2.md`, and `plans/v3.md`, not a replacement for them.
- You will ask Codex to implement exactly one numbered step at a time, with explicit instruction not to broaden scope.
- If a step is too large in practice, it should be split into a new numbered substep before implementation rather than implemented loosely.
