# Arodnap v1 Testing Plan

## Purpose

This document defines the engineering validation strategy for the restructured v1
implementation of Arodnap.

It validates the current tool on:

- supported real-world repositories that match the shipped v1 contract
- explicit unsupported-shape negatives that must fail closed
- deterministic local fixtures that keep regression coverage fast and repeatable

This plan is informed by Bryce Hojo's capstone evaluation of the older Arodnap
pipeline on real-world code. The main carry-forward risks are:

- normalization burden and project-specific setup cost
- multi-module and build-shape mismatch
- missing classpath or configuration inputs
- Java-version and toolchain sensitivity
- long runtimes on larger projects
- patch-application collisions and overlapping edits

The restructured v1 tool addresses these risks by narrowing support to root-level
single-module Gradle Java repositories, using a workspace copy by default, and
requiring unsupported shapes to fail closed rather than being normalized or
adapted manually.

For the numbered execution order, exact command templates, stop conditions, and
run-record format, use `plans/testing_v1_rulebook.md` together with this
strategy document.

## Non-goals

- Do not treat legacy normalized benchmarks as the public validation target.
- Do not broaden this plan into Maven support, Ant support, multi-module support,
  or Java-version modernization work.
- Do not turn this plan into an academic replication of all 9 survey repos.
- Do not require project-specific normalization scripts.

## Interfaces Under Test

This plan validates the current public CLI only:

- `analyze`
  - requires `manifest.json`, `report.json`, diagnostics, and adapter metadata
- `infer`
  - requires all `analyze` artifacts plus stabilized `inference/<label>/`
- `repair`
  - requires all `infer` artifacts plus stage logs, `stage_result.json`, and a
    patch manifest
- `apply`
  - requires dry-run patch validation and controlled mutation of the original
    repository only when explicitly invoked

This plan is doc-only. It does not require new code APIs, new config types, or
new public flags.

## Validation Layers

### Layer 1: Deterministic Local Regression

Use the existing unittest and fixture coverage as the fast regression base.

Required fixture families:

- `tests/fixtures/gradle-pipeline-baseline`
- `tests/fixtures/gradle-multimodule-unsupported`
- `tests/fixtures/gradle-nonstandard-layout-unsupported`

Required on every PR that touches CLI, adapters, analysis, stages, patching, or
output layout.

Recommended regression command:

```bash
python -m unittest \
  tests.test_v1_integration \
  tests.test_gradle_adapter \
  tests.test_analysis_commands \
  tests.test_analyze_once \
  tests.test_reanalyze \
  tests.test_rlc_runner \
  tests.test_wpi_runner
```

### Layer 2: Supported Real-World Smoke

Pin exact external repositories by commit SHA when this plan is executed.

Required supported positives:

- `codecov/example-java-gradle@b8290c0370a8b31fd21d9db3e8ce0de53b77b6ee`
  - primary always-on smoke
  - run `analyze`, `infer`, `repair`, and `apply`
- `gitpod-io/template-java-spring-boot-gradle@f09df7aecf75a26e92041307b112c9f7b2b241ca`
  - dependency-heavy supported-shape smoke
  - run `analyze` and `infer` on every pre-release validation
  - run `repair` as a manual pre-release check, not as a PR gate

### Layer 3: Explicit Unsupported-Shape Negatives

Use exact public repos that should be rejected by the current v1 contract.

Required negatives:

- `spring-guides/gs-spring-boot@f6d6868174a711e89b477a4d19fb1c6e024aa983`
  - reject at repo root because the actual examples live under subdirectories,
    so the root-level analysis contract is violated
- `apache/zookeeper@83221ec57e71a67877c17f71dcbf2cd3546f81aa`
  - reject because it is a Maven multi-module build

For unsupported negatives, run `analyze` only. Expected behavior is a clear,
early unsupported-project or missing-build-tool style failure with no mutation.

## Pass/Fail Rules

### Supported Positive Repos

- The command exits successfully.
- `manifest.json` and `report.json` exist and are internally consistent.
- `analyze` emits diagnostics and adapter metadata only.
- `infer` emits a stabilized inference directory and diagnostics.
- `repair` emits stage-local artifacts and a valid patch manifest.
- `apply` succeeds against the emitted patch manifest, including the empty-patch
  case.
- The original repo is unchanged before `apply`.

### Unsupported Negatives

- Failure is explicit and early.
- Arodnap does not try to normalize, guess, or partially adapt the repo shape.
- No mutation occurs in the original repo.

### Anomalies

Treat upstream tool noise in logs as an anomaly to record, not an automatic
failure, unless Arodnap itself fails or its artifact contract is broken.

Examples:

- warnings or stack traces emitted by the vendored Checker Framework tooling
- upstream Gradle deprecation warnings
- empty-patch outcomes on warning-free projects

## Execution Defaults

- Use shallow clones into a temp directory.
- Reuse a shared Gradle cache across workspace copies.
- Use a Python 3.10+ interpreter and record the exact interpreter path used.
- Store outputs outside the repo, for example under `/tmp/arodnap-validation`.
- Check out the pinned SHA in detached-head mode before running any commands.

Recommended defaults:

```bash
export GRADLE_USER_HOME=/tmp/arodnap-gradle-home
```

For each run, record:

- repo name
- pinned SHA
- Python version
- Java version
- Gradle wrapper version
- command run
- exit code
- warning count
- patch count
- whether `apply` changed files
- anomalies observed in logs

## Cadence

- **PR gate**
  - local regression layer
  - `codecov/example-java-gradle` full smoke
- **Pre-release / manual validation**
  - both supported external repos
  - both unsupported negatives
- **Quarterly / manual survey carry-forward review**
  - inspect the old 9 survey repos only to refresh rationale and note which
    remain out of scope
  - do not make the old survey set part of the required v1 gate

## Test Cases And Scenarios

| Target | Category | Commands to run | Expected outcome | Required artifacts or failure signal |
| --- | --- | --- | --- | --- |
| `tests/fixtures/gradle-pipeline-baseline` | fixture | `python -m unittest tests.test_v1_integration` | Repair/apply baseline passes through workspace-copy flow | successful test run covering `repair`, `apply`, stage history, and output layout |
| `tests/fixtures/gradle-multimodule-unsupported` | fixture | `python -m unittest tests.test_gradle_adapter` | Multi-module fixture is rejected | `UnsupportedProjectError` mentioning multi-module shape |
| `tests/fixtures/gradle-nonstandard-layout-unsupported` | fixture | `python -m unittest tests.test_gradle_adapter` | Nonstandard source layout fixture is rejected | `UnsupportedProjectError` mentioning `src/main/java` expectation |
| `codecov/example-java-gradle@b8290c0370a8b31fd21d9db3e8ce0de53b77b6ee` | supported-positive | `python -m arodnap.main analyze ...`; `infer ...`; `repair ...`; `apply --patch-dir ...` | Full CLI smoke succeeds on a simple supported repo | `manifest.json`, `report.json`, diagnostics, inference dir, stage artifacts, patch manifest; repo unchanged until `apply` |
| `gitpod-io/template-java-spring-boot-gradle@f09df7aecf75a26e92041307b112c9f7b2b241ca` | supported-positive | `python -m arodnap.main analyze ...`; `infer ...` | Supported-shape dependency-heavy smoke succeeds | `manifest.json`, `report.json`, diagnostics, adapter metadata, stabilized inference dir |
| `spring-guides/gs-spring-boot@f6d6868174a711e89b477a4d19fb1c6e024aa983` | unsupported-negative | `python -m arodnap.main analyze ...` | Root-level project shape is rejected | explicit unsupported-project failure caused by subdirectory project layout |
| `apache/zookeeper@83221ec57e71a67877c17f71dcbf2cd3546f81aa` | unsupported-negative | `python -m arodnap.main analyze ...` | Unsupported build shape is rejected | explicit unsupported-project or missing-build-tool style failure caused by Maven multi-module layout |

## Assumptions And Defaults

- The deliverable is `plans/testing_v1.md`.
- `plans/README.md` links to this file as a validation companion to the roadmap.
- This plan applies to the current restructured implementation only.
- Pinned SHAs are the current defaults and must be updated explicitly when the
  validation set is refreshed.
- The capstone survey is used to justify validation categories and risks, not to
  force the new tool back into a normalization-based benchmark workflow.
