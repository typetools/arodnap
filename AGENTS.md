# AGENTS.md

This file is the implementation playbook for Codex, Claude, and similar repository agents working on Arodnap.

## Purpose

Use this file together with the canonical roadmap under `plans/`. The roadmap defines what to build. This file defines how to work in this repo without accidentally following legacy behavior or reintroducing old benchmark assumptions.

## Source Of Truth Order

Use the repo sources in this order:

1. `plans/README.md`
2. `plans/v1.md`
3. `plans/v1_1.md`
4. `plans/v2.md`
5. `plans/v3.md`
6. current code for implementation context

The Notion plan is source material only. If it disagrees with the repo plans, follow the repo plans.

## Current Repo Overview

- Python at repo root currently orchestrates the existing pipeline.
- Java subtools under `restructure_plugins/` handle source transformations and patch materialization.
- `rlfixer/` contains the WALA-based RLFixer logic and supporting jars and tests.
- `checker_framework/` contains the vendored Checker Framework distribution and related stubs.

## Current-State Warnings

- The current root scripts are legacy-oriented and benchmark-oriented.
- `run_arodnap.py` still mutates the target project directly and still invokes field enhancement.
- `BenchmarkCleaner.restore_benchmark()` uses destructive git cleanup and is not a valid future public behavior.
- Field enhancement is currently unsafe for modernization work because it generates many non-compilable patches.
- The local `helpers/wpi.sh` is a normalized-layout helper, not the future build-backed WPI abstraction.
- Current stage patch handling assumes patches live under `src/` and must not leak into new abstractions.

## Non-Negotiable Constraints

- Do not design new code around normalized `src/lib/info` benchmark inputs.
- Do not include field enhancement in v1.
- Do not mutate the original target repo by default.
- Do not use `os.system` in new code.
- Do not add new mutable global runtime config patterns like the current `Constants.py` approach.
- All new mutation-capable stages must emit structured `StageResult` outputs.
- All analysis reruns must go through `reanalyze(workspace)`.
- Keep the legacy normalized workflow internal until the build-backed path is stable.

## Implementation Order

Follow this order unless the plan is explicitly updated:

1. finish v1 contracts and workspace model
2. add the Gradle adapter
3. implement `reanalyze(workspace)`
4. wrap close injector and owning-field stages
5. add RLFixer compatibility bundle generation
6. wire RLFixer and RLPatcher through structured stage wrappers
7. update public docs after the v1 flow is in place
8. complete v1.1 cleanup, hardening, and operability work that preserves v1 scope
9. only then begin v2 expansion

Do not skip directly to Maven, Ant, multi-module, dependency modernization, or
publishable packaging work before the v1 and v1.1 foundations are solid.

## Practical Repo Notes

- The current public README documents a normalized project layout. That is legacy documentation and should not drive new architecture.
- The current RLFixer invocation depends on `jarfile/`, `info/classes`, and `info/sources`. The first migration step is a compatibility bundle generator, not a direct RLFixer rewrite.
- The current patch helpers infer strip levels from `src/` paths. New code should move that logic behind stage-local abstractions and patch manifests.
- The new public WPI path should use the official Checker Framework `wpi.sh` from the vendored Checker Framework tree, not the repo helper script.

## Expectations For Implementation Agents

- Start from `plans/v1.md` for core v1 behavior.
- Start from `plans/v1_1.md` for cleanup, hardening, or operability work that
  preserves the v1 support boundary.
- If scope changes materially, update the relevant plan file before or alongside the code change.
- Record assumptions explicitly in docs or structured config instead of hiding them in code.
- Prefer adapter-driven design over one-off path assumptions.
- Keep machine-readable outputs stable once introduced.
- Use the rule: discover, normalize, verify, fail closed.
- `arodnap doctor` is the only planned additive public CLI command in v1.1.

### Discover, Normalize, Verify, Fail Closed

- Discover build facts from Gradle or explicit adapter metadata, not from ad hoc path guesses.
- Normalize legacy tool outputs before exposing them outside the stage wrapper.
- Verify final artifacts, especially patch bundles, before reporting success.
- Fail closed when a repo shape is unsupported or when an artifact cannot be validated.

In practice:

- do not guess source roots or dependency roots outside the adapter contract
- do not let raw stage-emitted `src/`-relative patches leak into orchestrator logic
- do not report a final patch bundle as valid until dry-run patch application succeeds against a clean copy of the original repo

## What To Preserve During Migration

- Preserve one internal normalized legacy regression path until the new workflow is covered by tests.
- Preserve the language split: Python orchestrator, Java analysis and transformation tools.
- Preserve existing Java tools where possible by wrapping them, not rewriting them all at once.

## What To Avoid

- Do not build new features on top of `run_arodnap.py` as the permanent architecture.
- Do not make field enhancement part of the forward plan until it is proven safe in a later, explicit milestone.
- Do not expose the compatibility bundle as a user-facing requirement.
- Do not assume all future builds look like Gradle v1.

## Deliverables Expected From Future Work

When implementing roadmap work, expect to produce:

- new structured Python modules under the planned package layout
- stage-local logs and `stage_result.json` files
- `report.json` and run manifests
- updated plan docs if milestone scope changes

Keep docs and code aligned. The plans are not historical notes; they are active implementation contracts.

## How To Use Codex Incrementally

Do not ask for the whole roadmap at once. Work in thin vertical slices that each leave the repo in a coherent state.

Recommended slice order:

1. contracts and package skeleton
2. workspace copy and cleanup model
3. Gradle detection and compile validation
4. Gradle source-file and app-class extraction
5. Gradle classpath extraction and adapter metadata
6. build-backed `reanalyze(workspace)`
7. close-injector stage wrapper
8. owning-field stage wrapper
9. RLFixer compatibility bundle generation
10. RLFixer wrapper
11. RLPatcher wrapper and patch manifest
12. `apply` command and patch dry-run validation
13. docs and integration fixtures
14. v1.1 runtime consolidation and wrapper cleanup
15. v1.1 adapter hardening, `doctor`, and basic packaging

For each slice, ask for:

- the exact plan subsection being implemented
- the files or subsystem to touch
- the acceptance criteria
- the tests or checks to run
- explicit instruction not to broaden scope

Good prompt pattern:

- "Implement only the v1 workspace model from the plan. Do not start the Gradle adapter yet. Add tests for workspace copy and cleanup."
- "Implement only Gradle source-file and app-class extraction. Use the existing plan contracts. Do not wire WPI or stages yet."
- "Implement only the close-injector stage wrapper and normalize its patch outputs. Do not change RLFixer or apply flow."

The goal is to make each request:

- one milestone slice
- one acceptance boundary
- one verification story

If a slice reveals a plan gap, update the relevant plan before continuing to the next slice.
