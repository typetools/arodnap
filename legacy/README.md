# Legacy: the paper's evaluation pipeline

This directory keeps the scripts and tools used for the experiments in "Repairing Leaks
on Resource Wrappers". They are kept for reference and are **not maintained**. For
repairing your own project, use the `arodnap` tool described in the top-level
[README](../README.md).

## What is here

| Path | What it is |
|---|---|
| `run_arodnap.py` and the other `*.py` files | the original pipeline scripts (`Constants.py` holds their configuration) |
| `helpers/wpi.sh`, `helpers/ep.sh` | whole-program inference and field enhancement for the normalized layout |
| `rlfixer/lib`, `rlfixer/classes` | RLFixer as used in the paper: WALA 1.5.7, JavaParser 3.24.7, and its compiled classes (`rlfixer/out` and the IntelliJ files are that build's IDE output) |
| `rlpatcher-paper-patches/` | patches RLPatcher produced during the paper's evaluation |
| `fixtures/legacy-pipeline-baseline/` | a small project in the normalized layout, with the script that generated it from `test-projects/gradle-pipeline-baseline` |

The scripts also need tools that are not in this directory:

- the Java stage tools (AutoCloseInjector, OwningFieldFixer, RLPatcher) of an Arodnap
  distribution: set `ARODNAP_HOME` to it (`mvn package` builds one under
  `arodnap-distribution/target/`);
- Checker Framework 3.49.0, from its
  [release](https://github.com/typetools/checker-framework/releases/tag/checker-framework-3.49.0),
  unpacked into `legacy/checker-framework-3.49.0`;
- the stubs in `arodnap-engine/src/main/resources/org/arodnap/engine/stubs`.

The tools and stubs have changed since the paper, so results can differ from the published ones.

## Requirements

- Java 11 (WALA 1.5.7 cannot read class files of newer JDKs)
- GNU `patch` and `dos2unix`
- Python 3

## Project layout it expects

```
<project-root>/
├── src/          Java sources
├── lib/          dependency jars
└── info/
    ├── sources   source files relative to the project root, one per line
    └── classes   fully qualified class names to analyze, one per line ($ for nested classes)
```

## Running

```bash
python3 legacy/run_arodnap.py --source_project_folder <project-root>
```

Optional: `--rlc_results_folder`, `--rlfixer_results_folder`, `--patch_and_logs_folder`
(default: folders under `tool_results/` in the current directory).

**Warning:** these scripts edit the project in place, and `BenchmarkCleaner` resets it
with `git` commands that discard changes. Run them only on a disposable copy.
