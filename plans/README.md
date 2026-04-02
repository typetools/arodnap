# Arodnap Modernization Plans

This directory is the canonical modernization roadmap for Arodnap. The content originated from the Notion plan at `Arodnap modernization plan`, but the repo copies are the source of truth from this point forward and have been corrected against the current repository state.

## Current Repo Reality

- The active public entrypoint is `run_arodnap.py`, which orchestrates the pipeline in Python and still assumes a normalized project layout.
- The current public README documents a normalized `src/`, `lib/`, `info/` benchmark format and says patches are automatically applied.
- The current pipeline mutates the target project directly and calls `BenchmarkCleaner.restore_benchmark()`, which uses `git restore` and `git clean`.
- The current forward pipeline is: normalize line endings, field enhancement, Checker Framework Resource Leak Checker plus WPI, close injector, rerun analysis, owning-field fixer, rerun analysis, RLFixer, then RLPatcher materialization.
- Field enhancement is currently not safe for the modernization effort because it produces many non-compilable patches.
- The local `helpers/wpi.sh` is a repo-specific helper for the normalized layout. It is not the long-term WPI abstraction.
- RLFixer is still coupled to `jarfile/`, `info/classes`, and `info/sources`, which is why the modernization plan keeps a compatibility layer before attempting a deeper RLFixer rewrite.

## Plan Split

- `v1.md`: first usable productization milestone. Standard single-module Gradle only, temp workspace by default, structured pipeline, legacy path kept internal.
- `v1_1.md`: cleanup and hardening milestone before build-system expansion. Runtime consolidation, wrapper and adapter cleanup, `doctor`, richer diagnostics, and basic installability while preserving the v1 support boundary.
- `v2.md`: build-system and project-shape expansion milestone. Maven, Ant, multi-module support, config, checkpoints, resume, and broader patch workflows.
- `v3.md`: publishable tool milestone. Dependency modernization, compatibility policy, release engineering, migration notes, and release-grade packaging.
- `testing_v1.md`: engineering validation companion for the current restructured v1 implementation.
- `testing_v1_rulebook.md`: numbered step-by-step checklist for executing the v1 validation plan during implementation.

`v1_1.md` is an inserted milestone, not a renumbering. Its purpose is to reduce
the cost and risk of v2 adapter work without widening supported project shape.

## Locked Decisions

- Repo docs are canonical over Notion.
- Python remains the outer orchestrator.
- Java remains the analysis and transformation layer.
- Field enhancement is excluded from v1 because it currently produces non-compilable patches.
- Field enhancement stays out of the v1 stage sequence even though it currently runs before RLC in the legacy pipeline.
- The legacy normalized-project workflow remains internal during migration and is kept only as a compatibility and regression path.
- The original target repo must not be mutated by default.
- `reanalyze(workspace)` is the central analysis primitive for the new architecture.

## How To Use These Plans

- Start with `v1.md` for core behavior and public v1 contract work.
- Use `v1_1.md` for cleanup, hardening, and operability work that preserves the
  v1 support boundary.
- Use `AGENTS.md` for repo context, invariants, execution order, and implementation constraints.
- Treat `v2.md` and `v3.md` as follow-on roadmaps, not as permission to skip unresolved v1 work.
- If implementation scope changes materially, update the relevant plan file before changing code.
