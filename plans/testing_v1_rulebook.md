# Arodnap v1 Testing Rulebook

This rulebook turns [testing_v1.md](/Users/sanjay/projects/arodnap/plans/testing_v1.md)
into a step-by-step execution checklist. Use it while implementing. Refer to
steps by number in commits, notes, and issue threads.

## How To Use This Rulebook

- Start at `Step 0` for a new validation session.
- During normal implementation, run only the steps required for the scope you
  changed.
- If a step fails, stop there. Do not mark later steps complete.
- Record each completed step using the run-record template in `Step 6`.
- Treat this as the operator procedure. Keep
  [testing_v1.md](/Users/sanjay/projects/arodnap/plans/testing_v1.md) as the
  strategy document.

## Scope Map

- Normal development: `Step 0`, `Step 1`
- Risky merge gate: `Step 0`, `Step 1`, `Step 2`
- Pre-release or demo validation: `Step 0` through `Step 6`
- Quarterly carry-forward review: revisit
  [testing_v1.md](/Users/sanjay/projects/arodnap/plans/testing_v1.md), but do
  not turn the old 9-repo survey set into a required gate

## Step 0: Set Up The Validation Session

Use a Python 3.10+ interpreter.

Recommended environment:

```bash
export ARODNAP_PYTHON=/opt/homebrew/bin/python3
export GRADLE_USER_HOME=/tmp/arodnap-gradle-home
export ARODNAP_VALIDATION_ROOT=/tmp/arodnap-validation
mkdir -p "$ARODNAP_VALIDATION_ROOT"
mkdir -p "$GRADLE_USER_HOME"
```

Record these once per session:

```bash
"$ARODNAP_PYTHON" --version
java -version
```

Complete `Step 0` only when:

- Python and Java versions are recorded
- `GRADLE_USER_HOME` exists
- `ARODNAP_VALIDATION_ROOT` exists

Stop here if:

- Python is below 3.10
- Java is missing
- the environment cannot create the temp directories

## Step 1: Run Deterministic Local Regression

Run exactly:

```bash
"$ARODNAP_PYTHON" -m unittest \
  tests.test_v1_integration \
  tests.test_gradle_adapter \
  tests.test_analysis_commands \
  tests.test_analyze_once \
  tests.test_reanalyze \
  tests.test_rlc_runner \
  tests.test_wpi_runner
```

This is the minimum gate for any change that touches:

- CLI behavior
- adapters
- analysis runners
- stage wrappers
- patching or apply behavior
- output layout, manifests, or report contract

Complete `Step 1` only when:

- the test command exits `0`
- `tests/fixtures/gradle-pipeline-baseline` still passes
- `tests/fixtures/gradle-multimodule-unsupported` still rejects cleanly
- `tests/fixtures/gradle-nonstandard-layout-unsupported` still rejects cleanly

Stop here if:

- any unittest fails
- unsupported-shape classification changes unexpectedly
- `tests.test_v1_integration` shows an apply-flow regression

## Step 2: Validate The Primary Supported Smoke Repo

Repo:

- `codecov/example-java-gradle`
- pinned SHA: `b8290c0370a8b31fd21d9db3e8ce0de53b77b6ee`

Prepare the repo:

```bash
rm -rf "$ARODNAP_VALIDATION_ROOT/codecov-example-java-gradle"
git clone --depth 1 https://github.com/codecov/example-java-gradle.git \
  "$ARODNAP_VALIDATION_ROOT/codecov-example-java-gradle"
git -C "$ARODNAP_VALIDATION_ROOT/codecov-example-java-gradle" fetch --depth 1 origin \
  b8290c0370a8b31fd21d9db3e8ce0de53b77b6ee
git -C "$ARODNAP_VALIDATION_ROOT/codecov-example-java-gradle" checkout \
  --detach b8290c0370a8b31fd21d9db3e8ce0de53b77b6ee
```

Sanity-check the target build:

```bash
GRADLE_USER_HOME="$GRADLE_USER_HOME" \
  "$ARODNAP_VALIDATION_ROOT/codecov-example-java-gradle/gradlew" \
  -p "$ARODNAP_VALIDATION_ROOT/codecov-example-java-gradle" classes
```

Run these commands in order:

```bash
GRADLE_USER_HOME="$GRADLE_USER_HOME" "$ARODNAP_PYTHON" -m arodnap.main analyze \
  --out-dir "$ARODNAP_VALIDATION_ROOT/out-codecov-analyze" \
  "$ARODNAP_VALIDATION_ROOT/codecov-example-java-gradle"

GRADLE_USER_HOME="$GRADLE_USER_HOME" "$ARODNAP_PYTHON" -m arodnap.main infer \
  --out-dir "$ARODNAP_VALIDATION_ROOT/out-codecov-infer" \
  "$ARODNAP_VALIDATION_ROOT/codecov-example-java-gradle"

GRADLE_USER_HOME="$GRADLE_USER_HOME" "$ARODNAP_PYTHON" -m arodnap.main repair \
  --out-dir "$ARODNAP_VALIDATION_ROOT/out-codecov-repair" \
  "$ARODNAP_VALIDATION_ROOT/codecov-example-java-gradle"

GRADLE_USER_HOME="$GRADLE_USER_HOME" "$ARODNAP_PYTHON" -m arodnap.main apply \
  --out-dir "$ARODNAP_VALIDATION_ROOT/out-codecov-repair" \
  --patch-dir "$ARODNAP_VALIDATION_ROOT/out-codecov-repair/patches" \
  "$ARODNAP_VALIDATION_ROOT/codecov-example-java-gradle"
```

Verify after the command sequence:

- `out-codecov-analyze/manifest.json` exists
- `out-codecov-analyze/report.json` exists
- `out-codecov-analyze/diagnostics/` exists
- `out-codecov-infer/inference/initial` exists
- `out-codecov-repair/stages/` exists
- `out-codecov-repair/patches/manifest.json` exists

Post-check the original repo:

```bash
git -C "$ARODNAP_VALIDATION_ROOT/codecov-example-java-gradle" status --short
```

Complete `Step 2` only when:

- all four public commands exit `0`
- artifacts match the command contract
- the original repo remains unchanged before and after the empty-patch `apply`

Stop here if:

- any command exits non-zero
- required artifacts are missing
- the repo mutates unexpectedly

## Step 3: Validate The Second Supported Smoke Repo

Repo:

- `gitpod-io/template-java-spring-boot-gradle`
- pinned SHA: `f09df7aecf75a26e92041307b112c9f7b2b241ca`

Prepare the repo:

```bash
rm -rf "$ARODNAP_VALIDATION_ROOT/gitpod-template-java-spring-boot-gradle"
git clone --depth 1 https://github.com/gitpod-io/template-java-spring-boot-gradle.git \
  "$ARODNAP_VALIDATION_ROOT/gitpod-template-java-spring-boot-gradle"
git -C "$ARODNAP_VALIDATION_ROOT/gitpod-template-java-spring-boot-gradle" fetch --depth 1 origin \
  f09df7aecf75a26e92041307b112c9f7b2b241ca
git -C "$ARODNAP_VALIDATION_ROOT/gitpod-template-java-spring-boot-gradle" checkout \
  --detach f09df7aecf75a26e92041307b112c9f7b2b241ca
```

Sanity-check the target build:

```bash
GRADLE_USER_HOME="$GRADLE_USER_HOME" \
  "$ARODNAP_VALIDATION_ROOT/gitpod-template-java-spring-boot-gradle/gradlew" \
  -p "$ARODNAP_VALIDATION_ROOT/gitpod-template-java-spring-boot-gradle" classes
```

Run the required commands:

```bash
GRADLE_USER_HOME="$GRADLE_USER_HOME" "$ARODNAP_PYTHON" -m arodnap.main analyze \
  --out-dir "$ARODNAP_VALIDATION_ROOT/out-gitpod-analyze" \
  "$ARODNAP_VALIDATION_ROOT/gitpod-template-java-spring-boot-gradle"

GRADLE_USER_HOME="$GRADLE_USER_HOME" "$ARODNAP_PYTHON" -m arodnap.main infer \
  --out-dir "$ARODNAP_VALIDATION_ROOT/out-gitpod-infer" \
  "$ARODNAP_VALIDATION_ROOT/gitpod-template-java-spring-boot-gradle"
```

Optional manual pre-release extension:

```bash
GRADLE_USER_HOME="$GRADLE_USER_HOME" "$ARODNAP_PYTHON" -m arodnap.main repair \
  --out-dir "$ARODNAP_VALIDATION_ROOT/out-gitpod-repair" \
  "$ARODNAP_VALIDATION_ROOT/gitpod-template-java-spring-boot-gradle"
```

Complete `Step 3` only when:

- `analyze` exits `0`
- `infer` exits `0`
- artifacts match the command contract for both commands

Stop here if:

- the Gradle sanity build fails
- `analyze` or `infer` exits non-zero
- artifacts are incomplete

## Step 4: Validate Unsupported Negative A

Repo:

- `spring-guides/gs-spring-boot`
- pinned SHA: `f6d6868174a711e89b477a4d19fb1c6e024aa983`

Prepare the repo:

```bash
rm -rf "$ARODNAP_VALIDATION_ROOT/gs-spring-boot"
git clone --depth 1 https://github.com/spring-guides/gs-spring-boot.git \
  "$ARODNAP_VALIDATION_ROOT/gs-spring-boot"
git -C "$ARODNAP_VALIDATION_ROOT/gs-spring-boot" fetch --depth 1 origin \
  f6d6868174a711e89b477a4d19fb1c6e024aa983
git -C "$ARODNAP_VALIDATION_ROOT/gs-spring-boot" checkout \
  --detach f6d6868174a711e89b477a4d19fb1c6e024aa983
```

Run:

```bash
GRADLE_USER_HOME="$GRADLE_USER_HOME" "$ARODNAP_PYTHON" -m arodnap.main analyze \
  --out-dir "$ARODNAP_VALIDATION_ROOT/out-gs-spring-boot-analyze" \
  "$ARODNAP_VALIDATION_ROOT/gs-spring-boot"
```

Post-check the original repo:

```bash
git -C "$ARODNAP_VALIDATION_ROOT/gs-spring-boot" status --short
```

Complete `Step 4` only when:

- the command exits non-zero
- the failure is explicit and early
- the failure reflects the repo-root contract violation
- the repo remains unchanged

Stop here if:

- the command partially analyzes the repo instead of rejecting it
- the repo mutates
- the failure mode is ambiguous

## Step 5: Validate Unsupported Negative B

Repo:

- `apache/zookeeper`
- pinned SHA: `83221ec57e71a67877c17f71dcbf2cd3546f81aa`

Prepare the repo:

```bash
rm -rf "$ARODNAP_VALIDATION_ROOT/apache-zookeeper"
git clone --depth 1 https://github.com/apache/zookeeper.git \
  "$ARODNAP_VALIDATION_ROOT/apache-zookeeper"
git -C "$ARODNAP_VALIDATION_ROOT/apache-zookeeper" fetch --depth 1 origin \
  83221ec57e71a67877c17f71dcbf2cd3546f81aa
git -C "$ARODNAP_VALIDATION_ROOT/apache-zookeeper" checkout \
  --detach 83221ec57e71a67877c17f71dcbf2cd3546f81aa
```

Run:

```bash
GRADLE_USER_HOME="$GRADLE_USER_HOME" "$ARODNAP_PYTHON" -m arodnap.main analyze \
  --out-dir "$ARODNAP_VALIDATION_ROOT/out-zookeeper-analyze" \
  "$ARODNAP_VALIDATION_ROOT/apache-zookeeper"
```

Post-check the original repo:

```bash
git -C "$ARODNAP_VALIDATION_ROOT/apache-zookeeper" status --short
```

Complete `Step 5` only when:

- the command exits non-zero
- the failure is explicit and early
- the failure reflects the Maven multi-module unsupported shape
- the repo remains unchanged

Stop here if:

- the command attempts to adapt or normalize the repo
- the repo mutates
- the failure mode is ambiguous

## Step 6: Record Results And Decide Promotion

For each repo and command pair, copy this run record:

```text
Repo:
Pinned SHA:
Category:
Command:
Python:
Java:
Gradle wrapper:
Exit code:
Warning count:
Patch count:
Did apply change files?:
Artifacts checked:
Anomalies:
Disposition: PASS | FAIL | PASS WITH ANOMALIES
```

Use these disposition rules:

- `FAIL`
  - Arodnap command exits unexpectedly
  - required artifacts are missing
  - a supported-positive repo mutates before `apply`
  - an unsupported-negative repo is partially analyzed instead of rejected
- `PASS WITH ANOMALIES`
  - Arodnap satisfies its contract
  - upstream tooling prints warnings, stack traces, or deprecation noise
  - artifacts are still complete and internally consistent
- `PASS`
  - command behavior and artifact contract both match expectations cleanly

Promotion rules:

- A change is ready for normal merge confidence only after `Step 1` and
  `Step 2` are green.
- A change is ready for release confidence only after `Step 1` through
  `Step 5` are complete and recorded.
- If any step is `FAIL`, return to that step after the fix. Do not carry later
  steps forward as valid.
