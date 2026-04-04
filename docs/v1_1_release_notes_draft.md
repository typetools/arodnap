# Arodnap v1.1 Release Notes Draft

This is a draft summary of the implemented v1.1 milestone.

## Summary

v1.1 keeps the v1 public behavior and supported project boundary intact while
making the tool easier to install, debug, test, and extend.

The only additive public command in v1.1 is:

```bash
arodnap doctor /path/to/repo
```

## Highlights

- Added a shared runtime/process execution layer for analysis, adapter, stage,
  and patch flows.
- Introduced formal stage-wrapper and build-adapter contracts plus explicit
  registries.
- Kept Gradle as the only supported backend while making adapter selection and
  metadata more explicit.
- Added `arodnap doctor` for environment checks, supported-repo validation, and
  compile-target validation.
- Enriched `manifest.json`, `report.json`, and command/stage logs with more
  debugging metadata.
- Added editable-install support and the `arodnap` console entrypoint.
- Strengthened the fixture-based integration and structured-output regression
  test foundation.

## Unchanged Scope

v1.1 does not add:

- Maven support
- Ant support
- multi-module support
- custom source-set layout support
- direct-classpath RLFixer mode
- field enhancement in the public flow

Standard single-module Gradle remains the only supported public project shape.

## Operator Notes

- `repair` still runs on a workspace copy and emits a patch bundle instead of
  mutating the original repository.
- `apply` remains the only command that updates the original repository.
- `doctor` is the recommended first command when validating a new target repo or
  environment.

## Development Notes

- Install with `python -m pip install -e .`
- Use `arodnap <command>` as the preferred invocation style
- See [`docs/architecture.md`](/Users/sanjay/projects/arodnap/docs/architecture.md)
  and [`docs/testing.md`](/Users/sanjay/projects/arodnap/docs/testing.md) for
  the contributor-facing architecture and testing guidance
