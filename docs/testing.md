# Testing Conventions

This note documents the test conventions.

## Fixture Foundation

Fixture repositories live under
[`tests/fixtures/`](../tests/fixtures).

Current fixture usage:

- `gradle-pipeline-baseline/`: the main fixture (wrappers, owning fields, direct and try/catch leaks)
- `gradle-dependency-leak/`: a leak through a Maven Central dependency
- `gradle-multimodule/`: two Gradle modules with a leak in each
- `maven-dependency-leak/`: Maven with a Maven Central dependency
- `ant-vendored-jar/`: Ant compiling against a jar checked into `lib/`
- `javac-script/`: no build tool; captured with `-- ./build.sh`
- `javac-latin1/`: ISO-8859-1 sources compiled for Java 8; `infer` must use the
  build's encoding and release, and `repair` must stop with a clear message

Keep fixtures intentionally small. A single high-signal fixture is preferred to
many partially maintained ones.

## Real End-To-End Tests

[`tests/test_e2e_real.py`](../tests/test_e2e_real.py) runs `repair`, `apply`
and a compile of the result with the real toolchain and nothing mocked. Mocked
tests check wiring; this checks that the tools actually work together.

```bash
ARODNAP_E2E=1 python -m unittest tests.test_e2e_real
```

It needs JDK 17 or newer (it has passed on 17, 21, 23, 24 and 25) and `gradle`, `mvn` and
`ant` on `PATH` (tests for a missing tool are skipped), and network access or a warm
Gradle cache for `gradle-dependency-leak`. Set `ARODNAP_CHECKER_FRAMEWORK` to
run it against another Checker Framework distribution; both the bundled 4.2.3
and 3.49.0 pass. Add a
fixture and a test here for every newly supported project shape.

## Continuous Integration

[`.github/workflows/ci.yml`](../.github/workflows/ci.yml) runs on every pull
request and every push to `master`:

- `unit`: the unit tests on Python 3.10 to 3.14 (Ubuntu) and on macOS
- `java-tools`: builds and tests every Java tool with JDK 21 and fails if a
  committed jar in `restructure_plugins/prebuilt_plugin_jars/` does not match
  its source (`python scripts/java_tools.py build` then `check`)
- `e2e`: the real end-to-end tests on JDK 17, 21 and 25 with Gradle, Maven and
  Ant installed; a skipped test fails the job

[`.github/workflows/real-projects.yml`](../.github/workflows/real-projects.yml)
runs weekly, on demand, and on pull requests that change the project list. It
repairs real open-source projects listed in
[`scripts/real_projects.json`](../scripts/real_projects.json), each cloned
fresh from its own repository at a pinned release, and checks that `repair`
succeeds and that the leak counts match the recorded ones. Run the same
locally:

```bash
python scripts/real_projects.py run commons-csv   # or --all
```

When a change to Arodnap is meant to change the results, rerun with `--record`
and commit the new counts. To test a newer release of a project, change its
`ref` and record again.

## Fixture-Based Integration Tests

Current integration-style tests build on
[`tests/fixture_helpers.py`](../tests/fixture_helpers.py).

Preferred assertions for fixture-backed flows:

- the original fixture repo is not mutated by `analyze`, `infer`, `repair`, or
  `doctor`
- the workspace-copy behavior is preserved
- expected top-level artifacts are emitted under `arodnap-out/`
- stage-local artifacts exist where the output contract expects them
- patch bundles remain normalized and consumable by `apply`

Representative suites:

- [`tests/test_analysis_commands.py`](../tests/test_analysis_commands.py)
- [`tests/test_cli_integration.py`](../tests/test_cli_integration.py)

## Structured Output Regression Tests

A small structured-output regression layer lives in
[`tests/test_structured_output_regressions.py`](../tests/test_structured_output_regressions.py).

These tests intentionally avoid brittle full-file snapshotting. Instead they:

- run a fixture-backed command flow
- normalize temp paths, timestamps, and elapsed-time values
- compare only deterministic fields from:
  - `manifest.json`
  - `report.json`
  - representative `stage_result.json` files

When adding new machine-readable fields:

- keep the schema additive where practical
- normalize unstable values in test helpers
- assert only the parts of the contract that are meant to be stable

## Stage-Level Tests

Stage tests should verify the wrapper boundary, not just subprocess calls.

Preferred assertions:

- raw tool outputs are normalized inside the wrapper
- `stage_result.json` matches the returned `StageResult`
- stage-local logs and artifacts are written where expected
- invalid outputs fail closed with clear errors

Representative suites:

- [`tests/test_close_injector_stage.py`](../tests/test_close_injector_stage.py)
- [`tests/test_owning_field_stage.py`](../tests/test_owning_field_stage.py)
- [`tests/test_rlfixer_stage.py`](../tests/test_rlfixer_stage.py)
- [`tests/test_rlpatcher_stage.py`](../tests/test_rlpatcher_stage.py)

## Contributor Guidance

When adding tests:

- start from the smallest fixture or helper that exercises the real contract
- prefer adapter-driven and stage-driven assertions over path-assumption tests
- keep the original-repo non-mutation property explicit
- do not build on the paper's scripts in `legacy/`
- only add golden-style coverage after unstable fields are normalized first
