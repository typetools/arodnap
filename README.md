# Arodnap

Arodnap repairs Java resource leaks. It is the tool from the paper
"Repairing Leaks on Resource Wrappers": it finds leaks with the Checker
Framework's Resource Leak Checker, fixes resource wrapper classes and owning
fields, and turns RLFixer's suggestions into source patches.

It works on Gradle (including multi-module builds), Maven and Ant projects, and
on any other build that runs `javac`, by watching the project's own build. It
analyzes a temporary copy of the project, never edits the original repository
unless you run `apply`, and produces one reviewable patch that has been
verified to apply.

## Install

For local development, install Arodnap from a checkout of this repository in
editable mode:

```bash
python -m pip install -e .
```

That installs the `arodnap` console entrypoint. The existing module-based
entrypoint still works:

```bash
python -m arodnap.main analyze /path/to/repo
```

## Prerequisites

- Python 3.10 or newer
- JDK 17 or newer, as `JAVA_HOME` or as the first `java` on `PATH`. The whole
  analysis (whole-program inference, the Resource Leak Checker, RLFixer and the
  repair tools) runs on this one JDK; it has been tested on 17, 21, 23 and 24.
  The bundled Checker Framework 4.2.3 is tested upstream up to JDK 26, and
  `doctor` warns (but does not stop you) on newer JDKs. Your project itself may
  target any release this JDK can compile.
- whatever the project's build needs: `./gradlew` or `gradle`, `./mvnw` or
  `mvn`, `ant`, or the tools your own build command uses
- GNU `patch`, exposed as `gpatch` or `patch`

`arodnap doctor` checks all of these.

## Commands

```bash
arodnap analyze /path/to/repo
arodnap infer /path/to/repo
arodnap repair /path/to/repo
arodnap apply --patch-dir ./arodnap-out/patches /path/to/repo
arodnap doctor /path/to/repo
```

`analyze`, `infer`, `repair` and `doctor` accept the project's build command
after `--`:

```bash
arodnap repair /path/to/repo -- ./build.sh           # any build that runs javac
arodnap repair /path/to/repo -- mvn -Pci compile      # override the default command
arodnap repair /path/to/repo -- ./gradlew :app:compileJava
```

Command summary:

- `analyze`: run one build-backed Resource Leak Checker pass and emit
  diagnostics plus adapter metadata.
- `infer`: run one `reanalyze(workspace)` cycle and preserve inferred `.ajava`
  outputs.
- `repair`: run the full workspace-based repair flow: analysis, close injector,
  owning-field repair, RLFixer fix suggestions, RLPatcher materialization, a
  final reanalysis, and one verified patch bundle covering every change.
- `apply`: validate a previously emitted patch bundle with a dry run, then
  apply it to the original repository. This is the only public command that
  mutates the original repository.
- `doctor`: run environment checks plus adapter-backed repository support and
  compile-target validation. It also writes `arodnap-out/doctor.json`.

Common options:

- `--out-dir`: output root. Defaults to `./arodnap-out`.
- `--keep-workspace`: keep the temporary workspace after the command exits.
- `--build-args`: repeatable extra arguments for the default build command.
- `--compile-target`: the Gradle task, Maven phase or Ant target the default
  build command runs (`compileJava`, `compile`, and Ant's default target).
- `--checker-framework`: use another Checker Framework distribution (the
  directory containing `checker/dist/checker.jar`). Defaults to
  `$ARODNAP_CHECKER_FRAMEWORK`, then the bundled 4.2.3.
- `apply` also requires `--patch-dir`, usually `./arodnap-out/patches`.

## Typical Workflow

```bash
arodnap doctor /path/to/repo
arodnap repair /path/to/repo
arodnap apply --patch-dir ./arodnap-out/patches /path/to/repo
```

The intended flow is:

1. Run `doctor` first to confirm the environment and that Arodnap can capture
   the project's build.
2. Run `repair` to analyze a workspace copy and emit a normalized patch bundle.
3. Inspect `report.json`, `manifest.json`, stage outputs, and the patch bundle.
4. Run `apply` only when you are ready to update the original repository.

## How Arodnap Sees Your Build

Arodnap runs the project's build once per analysis point and records every
`javac` call through the build tool's own extension point:

| Build | Detected by | Default command | How javac calls are recorded |
|---|---|---|---|
| Gradle | `build.gradle(.kts)` or `settings.gradle(.kts)` | `gradle[w] clean compileJava` | init script on every `JavaCompile` task |
| Maven | `pom.xml` | `mvn[w] clean compile` | compiler plugin forks to a recording `javac` |
| Ant | `build.xml` | `ant` (default target) | recording Ant compiler adapter |
| anything else | `-- <command>` | none | recording `javac` first on `PATH` |

All recorded sources are analyzed together (every module of a multi-module
build), with the dependencies the build resolved. Files the build generates,
such as annotation processor output, are analyzed but never patched.

Not supported yet (Arodnap stops with a clear message):

- Lombok, which rewrites code during compilation
- builds that compile Java without calling `javac` or a supported build tool
  (for example a script that uses `$JAVA_HOME/bin/javac` directly)
- Kotlin and Android sources (Java sources in the same build are analyzed)

## Workspace And Mutation Model

`analyze`, `infer`, and `repair` operate on a temporary workspace copy. They do
not edit the original repository by default.

`repair` is intentionally non-destructive:

- Arodnap copies the target repository into a temporary workspace.
- Mutation-capable stages run only against that workspace copy, and analysis
  is rerun after each stage that changes sources.
- Each stage writes its own directory under `arodnap-out/stages/<stage>/`.
- The `bundle` stage diffs the original repository against the final workspace
  into one patch, replays it onto a clean copy of the original files to check
  it reproduces the repair exactly, and emits it under `arodnap-out/patches/`.

`apply` is intentionally separate:

- it validates the emitted patch bundle with a dry run against a clean copy of
  the original repository
- it only mutates the original repository after that validation succeeds

## Output Artifacts

By default Arodnap writes to `./arodnap-out`. The exact top-level files depend
on the command:

```text
arodnap-out/
  doctor.json           # doctor only
  report.json           # analyze / infer / repair
  manifest.json         # analyze / infer / repair
  diagnostics/
  inference/
  logs/
    <analysis-label>/
  stages/
    close_injector/
    owning_field/
    rlfixer/
    rlpatcher/
    bundle/
  patches/
    manifest.json
    arodnap.patch
```

Important outputs:

- `doctor.json`: machine-readable environment and supportability checks from
  `doctor`
- `report.json`: top-level run summary with adapter, analysis, and stage timing
  metadata
- `manifest.json`: top-level manifest with config, workspace, stage history,
  and artifact references
- `diagnostics/*.txt`: one diagnostics file per analysis point
- `logs/<label>/`: analysis logs and adapter metadata files
- `inference/<label>/`: preserved inferred outputs for `infer` and `repair`
- `stages/<name>/stage_result.json`: structured stage results for `repair`
- `patches/manifest.json` and `patches/arodnap.patch`: the verified patch
  bundle consumed by `apply`; review `arodnap.patch` before applying

## Troubleshooting

- If `doctor` says the build compiled no Java sources, make sure the build
  command compiles the main sources and does not skip compilation as up to
  date (the default commands clean first).
- The captured build's output and the recorded `javac` calls are in
  `arodnap-out/logs/<label>/build.log` and `javac-invocations.jsonl`.
- If `repair` or `apply` fails with a patch prerequisite error, install GNU
  `patch` and expose it as `gpatch` or `patch`.
- If the default build command is wrong for the repository, pass your own after
  `--` or use `--compile-target`.
- If analysis fails on a very new JDK, run `arodnap doctor`: it says whether
  the JDK is newer than the Checker Framework is tested on. Use a tested JDK
  via `JAVA_HOME`, or a newer Checker Framework via `--checker-framework`.
- If you need to inspect how a stage behaved, look at
  `arodnap-out/stages/<stage>/stage_result.json` and `stage.log` before
  rerunning the command.

## Running Tests

Run the full test suite:

```bash
python -m unittest discover -s tests -p "test_*.py"
```

Those tests mock the external tools. The real end-to-end tests run the real
builds (Gradle, Maven, Ant and a plain javac script),
whole-program inference, the Resource Leak Checker and every repair tool with
nothing mocked, then apply the patch and compile the result. They take about a
half a minute per fixture and are opt-in:

```bash
ARODNAP_E2E=1 python -m unittest tests.test_e2e_real
```

Run focused suites:

```bash
python -m unittest tests.test_doctor
python -m unittest tests.test_v1_integration
python -m unittest tests.test_structured_output_regressions
```

See [`docs/testing.md`](docs/testing.md) for conventions, fixture guidance, and the full representative suite list.

## Contributor Notes

Contributor-facing architecture and test guidance lives in:

- [`docs/architecture.md`](docs/architecture.md)
- [`docs/testing.md`](docs/testing.md)
- [`docs/v1_1_release_notes_draft.md`](docs/v1_1_release_notes_draft.md)

The legacy normalized-layout benchmark path is still preserved internally for
regression coverage, but it is not the preferred public usage story and should
not drive new architecture.
