# Arodnap Architecture

Arodnap is a Java engine that runs the paper's analysis and repair tools on a copy of a
project, and three front ends that tell it what the project is: a command line and plugins
for Maven and Gradle.

## Modules

| Module | What it is |
|---|---|
| `arodnap-model` | records every module shares: `CompileUnit`, `ProjectInputs`, `StageResult`, `BuildDescription` |
| `arodnap-engine` | the pipeline: workspace, analysis, stages, patches, reports, `apply`, `doctor` |
| `tools/*` | the paper's Java tools and the build hooks, each built into one self-contained jar |
| `arodnap-cli` | the `arodnap` command: records the project's build and runs the engine |
| `arodnap-maven-plugin` | Maven goals over the whole reactor |
| `arodnap-gradle-plugin` | Gradle tasks over every project of the build (its own Gradle build) |
| `arodnap-distribution` | the command line as users download it, and its end-to-end tests |

The tools:

| Module | Role |
|---|---|
| `tools/field-transformations` | Error Prone checks `ResourceFieldCanBeFinal` and `ResourceFieldCanBeLocal` |
| `tools/close-injector` | AutoCloseInjector: the `close_injector` stage |
| `tools/owning-field-fixer` | OwningFieldFixer: the `owning_field` stage |
| `tools/rlfixer` | RLFixer (WALA 1.8.0): fix suggestions |
| `tools/rlpatcher` | RLPatcher: turns suggestions into patches |
| `tools/ant-capture`, `tools/maven-capture` | the command line's hooks for recording Ant and Maven builds |

Every tool runs as its own JVM: their dependencies (WALA, several JavaParser releases, Error
Prone) conflict, and a separate process isolates crashes, memory and time limits. The engine
knows each tool's coordinates (`org/arodnap/engine/tools.properties`, filled in from the
parent POM): the distribution bundles those artifacts, and the plugins resolve them from
Maven repositories. The Checker Framework comes from Maven Central as `checker`,
`checker-qual` and `checker-util`, laid out side by side as `checker.jar`, `checker-qual.jar`
and `checker-util.jar`.

## Public Flow

| Command line | Maven | Gradle |
|---|---|---|
| `arodnap analyze` | `arodnap:analyze` | `arodnapAnalyze` |
| `arodnap infer` | `arodnap:infer` | `arodnapInfer` |
| `arodnap repair` | `arodnap:repair` | `arodnapRepair` |
| `arodnap apply` | `arodnap:apply` | `arodnapApply` |
| `arodnap doctor` | `arodnap:doctor` | `arodnapDoctor` |

Behavioral boundaries:

- `analyze` and `infer` are analysis-only flows.
- `repair` runs the ordered repair stages against a workspace copy and emits one verified
  patch bundle.
- `apply` is the only command that changes the original repository.
- `doctor` is diagnostic-only.

## Front Ends

A front end's only job is to produce the project's `ProjectInputs` (through
`inputs.ProjectCapture`): its compile units (sources, classpath, release, encoding,
annotation processors, generated-source directories) in the workspace copy, merged by
`inputs.CompileUnits.merge` into one analysis universe. The merge takes every unit's sources
plus generated sources, the union of classpaths minus artifacts the build itself wrote, the
highest release level (at least 8), and an analysis root for RLFixer. It rejects what Arodnap
cannot analyze yet (Lombok). Everything after that is the engine's.

- **Command line** (`arodnap-cli/.../capture`): runs the project's build once, in the copy,
  with a recording hook installed through the build tool's own extension point:

  | Build | Hook |
  |---|---|
  | Gradle | an init script records the inputs of every `JavaCompile` task |
  | Maven | a core extension (`tools/maven-capture`) makes the compiler plugin fork to a recording `javac` |
  | Ant | a compiler adapter (`tools/ant-capture`) |
  | anything else (`-- <command>`) | a recording `javac` first on `PATH` |

  Every hook writes the same JSON lines (`{cwd, args}`), which `CompileUnits.load` parses.
  Capture state stays in `arodnap-state/` beside the copy, so the build never sees it.
- **Maven plugin** (`ReactorCapture`, `MavenCompileUnits`): reads each reactor module's
  compile source roots, classpath and compiler configuration; source roots under the build
  directory are generated sources.
- **Gradle plugin** (`RunTask.BuildCapture`): reads each project's `compileJava` task after it
  ran.

Plugins read paths in the project, so `inputs.Relocation` maps them into the copy.

`repair` captures the project once and reuses the capture at every analysis point: stages
only edit existing source files, so each analysis recompiles the current sources instead of
running the build again.

## Engine

`pipeline.Engine` runs a command: it copies the project (`pipeline.Workspace`, without
`.git`), asks the front end for the inputs, runs the analyses and stages, and writes the
report. Its collaborators:

- `process.CommandRunner`: the one way anything runs a process. `ProcessCommandRunner`
  drains output, kills the process tree at a time limit (`--build-timeout`,
  `--analysis-timeout`, `--stage-timeout`; none by default) and returns a `CommandResult`,
  which also renders the command logs. Pipeline tests replace it with a fake.
- `jdk.JdkLocator`: the JDK everything runs on, `JAVA_HOME` or else the `java` on `PATH`.
- `tools.Toolchain`: where the tools are. The command line reads its distribution
  (`cli.Installation`); the plugins resolve artifacts (`Toolchain.fromArtifacts`).

### Analysis

`analysis.Analyzer` compiles the program into `arodnap-state/analysis-classes` (so app
classes and RLFixer's classpath do not depend on the build's output layout), runs
whole-program inference, then the Resource Leak Checker with the paper's flags, including
`-Adetailedmsgtext`, `-Astubs` (Arodnap's stubs, `tools/Stubs`) and `-Xmaxwarns 10000`.
The Checker Framework runs as `<JDK>/bin/java -jar checker.jar` on a JDK at least as new as
its own minimum (read from `checker.jar`) and RLFixer's 17 (`tools.CheckerFramework`).

Inference does not use `wpi.sh`. `analysis.WholeProgramInference` runs the loop of
do-like-javac's WPI tool (`-Ainfer=ajava -Awarns`, each round reading the previous one's
output through `-Aajava`) on the captured sources and classpath, until two rounds write the
same files (at most 20). `WholeProgramInferenceTest` pins the Checker Framework release the
loop was compared with, so an upgrade fails until the loop is reviewed again.

### Stages

Each stage (`stages.Stage`) takes a `StageContext` (the run, the current analysis, earlier
stage results) and returns a `StageResult`; it writes `stage_result.json`, `stage.log` and
its artifacts under `stages/<name>/`. The rules:

- a stage runs its tool through the `CommandRunner` and reads the tool's output formats
  itself; RLFixer's and RLPatcher's are read and written only in `tools.RlFixerFormats`
- raw tool patches are normalized to repo-root-relative paths (`stages.ToolPatches`) before
  they leave the stage, and applied to the copy by the engine's own patch code
- a stage does not decide the order and does not rerun analysis
- a tool that crashes fails its stage, and the run; it never turns into "no results"

The order is declared once, in `Engine.REPAIR_STAGES`; a stage says whether a change it made
needs a new analysis and under which label (`Stage.reanalysisLabel`):

0. `field_transformations`, before the initial analysis so it costs no extra analysis run:
   the Error Prone checks in `tools/field-transformations` make private fields `final` (a
   temporary and `finally` when the assignment is inside `try`, only if nothing can observe
   the field in between) or turn them into local variables (skipped for fields with
   initializers, annotations, or names used in string literals). With
   `--field-transformations=resources` (default) only fields whose type can hold a resource
   are changed: a type is a resource if it is `AutoCloseable`, `@MustCall`, or a program
   class that disposes of a resource field in some method (recursively; the method name does
   not matter) or allocates one in its constructor. `all` changes every eligible field as the
   paper did; `off` skips the stage. Each edit is compile-checked for its file, then the whole
   program is compiled and edits in files with errors are undone. `field_changes.json` lists
   every field and whether it was kept.
1. `close_injector` (analysis `post_close_injector` if it changed sources)
2. `owning_field` (analysis `post_owning_field` if it changed sources)
3. `rlfixer`: runs RLFixer on the captured inputs; fails if RLFixer crashes or prints no
   fixes report
4. `rlpatcher`: materializes the suggestions RLFixer marks fixable (a warning RLFixer lists
   more than once is fixable if any of its rows is) and applies them to the copy, skipping
   any that conflict with an earlier patch (analysis `final` if it changed sources). Its
   `patch_manifest.json` records one outcome per suggestion under `fixes`: `materialized`,
   `no_change` (nothing to change), `rejected` (failed RLPatcher's compile check), `unsafe`
   (the close could only be placed by moving the allocation ahead of code that runs before
   it, which would change behavior), `unsupported`, `crashed` or `timed_out`
5. `bundle`: diffs the original repository against the final copy into one patch, byte for
   byte, and verifies it by replaying it onto a clean copy of the original files

The Java stage tools receive the captured source list, classpath, release and encoding
through `-Darodnap.sourcesFile`, `-Darodnap.classpathFile`, `-Darodnap.release` and
`-Darodnap.encoding`, and write raw patches to `-Darodnap.patchFile` in the stage directory.
The tools fall back to the paper's `src/` + `lib/` layout when these are not passed; the
engine always passes them.

### Patches

`patch.*` creates and applies unified diffs. Text is handled as bytes (ISO-8859-1), split
only at `\n`, so a file's line endings and encoding survive: context lines keep the file's
bytes, and inserted lines take the file's line ending. `patch.SequenceMatcher` is a port of
Python's `difflib.SequenceMatcher`, so patches stay hunk for hunk what earlier releases made.
`apply` (`apply.BundleApplier`) checks every file's SHA-256 against the one the patch was
made from, then applies the bundle to a copy first and to the project only when that works.

### Reports

`report.RunRecord` writes `manifest.json` and `report.json`; `report.LeakReport` follows
each leak warning through the analysis runs (by file, checker key and the
`-Adetailedmsgtext` fields, since patches shift lines) into `report.json`'s `leaks` section;
`report.HtmlReport` renders the same data as `report.html`; `report.Summary` prints the
closing summary.

## Outputs

Everything goes under the output directory (`arodnap-out/` for the command line,
`target/arodnap` of the top-level Maven project, `build/arodnap` for Gradle):

- `manifest.json`, `report.json`, `report.html`, `doctor.json`
- `diagnostics/<label>.txt`: each analysis's checker output
- `inference/<label>/`: inferred `.ajava` files
- `logs/<label>/`: the analysis's inputs and logs (`wpi.log`, `source-files.txt`,
  `app-classes.txt`, `classpath-entries.txt`, `adapter-metadata.json`)
- `stages/<stage>/`: `stage_result.json`, `stage.log` and the stage's artifacts
- `patches/`: `arodnap.patch` and its `manifest.json`, which `apply` reads

Important contracts:

- `manifest.json` is the run-level machine-readable ledger
- `report.json` is the run-level summary view; after `repair` its `leaks` section lists every
  `required.method.not.called` warning with `status` (`fixed` or `remaining`), `fixed_by` (the
  stage after which it disappeared), `reason` (a stable code; `leaks.reasons` maps codes to
  text) and `first_seen` (the analysis run that first reported it)
- `stages/<stage>/stage_result.json` is the stable stage result boundary
- `patches/manifest.json` is the patch bundle `apply` consumes

The snapshot test in `RepairPipelineTest` (`theOutputFilesKeepTheirShape`) locks the whole
shape of these files for a repair run: approved copies are in
`arodnap-engine/src/test/resources/snapshots`.

### Minimum Stable Output Contracts

These fields are the stable machine-readable contract. A change must either keep all of them
or update this section and the approved snapshots
(`mvn test -Darodnap.updateSnapshots=true`, then review the diff).

#### `manifest.json` (written by `analyze`, `infer`, `repair`)

| Key | Type | Notes |
|-----|------|-------|
| `success` | bool | `true` on a clean run |
| `error` | string \| null | error message if `success` is `false` |
| `error_type` | string \| null | exception class name if `success` is `false` |
| `build_system` | string | `gradle`, `maven`, `ant` or `command` |
| `adapter_name` | string | the build system, or `maven-plugin` / `gradle-plugin` |
| `legacy_regression_enabled` | bool | always `false` |
| `config` | object | includes `command`, `workspace_mode`, `keep_workspace`, `build_args`, `compile_target`, `checker_jar` |
| `workspace_root` | string | absolute path to the temporary workspace used |
| `current_analysis` | object \| null | the last analysis; `null` if analysis never completed |
| `stage_history` | array | one `StageResult` object per executed stage |
| `artifacts` | object | paths to `diagnostics_dir`, `inference_dir`, `logs_dir`, `stages_dir`, `patch_bundle_dir`, `report`, `manifest`, `patches_manifest` |
| `run_metadata` | object | includes `tool_version`, `command`, `repo_root`, `workspace_root`, `adapter_name`, `java_version`, `started_at`, `completed_at`, `elapsed_seconds` |
| `adapter` | object | includes `build_system`, `adapter_name`, `compile_target`, `source_root`, `compiled_classes_root` |
| `analysis_runs` | array | one entry per analysis with `label`, `success`, `warning_count`, timing |
| `stage_timings` | array | one entry per stage with `stage`, `success`, `changed`, `elapsed_seconds` |
| `stage_execution_summary` | object | includes `executed`, `changed`, `reruns_requested`, `successful`, `failed_attempts` |

#### `report.json` (written by `analyze`, `infer`, `repair`)

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
| `leaks` | object | `repair` only: `summary`, `warnings`, `reasons`, `field_changes`, `other_warnings`, `html_report` |

#### `doctor.json` (written by `doctor`)

| Key | Type | Notes |
|-----|------|-------|
| `success` | bool | `true` when no check has status `"error"` |
| `repo_root` | string | |
| `out_dir` | string | |
| `build_args` | array | |
| `compile_target` | string \| null | |
| `checks` | array | one object per check |

Each check object:

| Key | Type | Notes |
|-----|------|-------|
| `name` | string | stable check identifier, e.g. `"java_runtime"` |
| `status` | string | one of `"ok"`, `"warning"`, `"error"` |
| `message` | string | human-readable summary |
| `details` | object \| null | optional structured detail |

Stable `name` values: `java_runtime`, `checker_framework_path`, `checker_framework_tools`,
`plugin_jars`, `repo_path`, `adapter_selection`, `repo_support`, `source_root`,
`compile_target`.

#### `stages/<stage>/stage_result.json` (written by each repair stage)

| Key | Type | Notes |
|-----|------|-------|
| `stage` | string | stage name, e.g. `"close_injector"` |
| `changed` | bool | `true` if the stage changed workspace sources |
| `changed_files` | array of strings | repo-root-relative paths of changed files |
| `rerun_required` | bool | `true` if an analysis must follow this stage |
| `artifacts` | object | string → path map of stage-local artifacts; always includes `"log"` |
| `notes` | array of strings | human-readable stage notes |
| `success` | bool | `true` if the stage completed without error |

### Changing An Output Contract

Before a change alters the structure or meaning of the outputs above:

1. Update this section to describe the new contract.
2. Update the approved snapshots and review their diff.
3. If a field is removed or renamed, call it out as a breaking change in the commit message.

Additive changes (new optional fields) need only the snapshot update.

## Legacy Boundary

The paper's original normalized-layout pipeline lives in `legacy/` for reference. Nothing
else uses it, and it is not the architecture to build on.

When extending Arodnap, prefer:

- build-model or recorded-build discovery over path guessing
- workspace-copy behavior over in-place changes
- stage-local normalization over leaking raw tool outputs
- structured artifacts over ad hoc side effects
