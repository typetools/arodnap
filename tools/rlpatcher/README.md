# RLPatcher

`RLPatcher` turns RLFixer suggestions into concrete source patches. The engine
runs it once per suggestion in the `rlpatcher` stage, normalizes each patch, and
applies the ones that do not conflict to the workspace copy.

## Inputs And Outputs

```bash
java -jar target/arodnap-rlpatcher-<version>.jar --prompt /path/to/prompt.json --project-root /path/to/project
```

- reads a prompt the engine writes from a matched RLFixer suggestion and leak
  warning (`RlFixerFormats.rlpatcherPrompt`)
- analyzes source files under `--project-root`
- writes a unified diff named `rlfixer.patch` in the current working directory

The engine copies the patch into `stages/rlpatcher/` of the output directory,
rewrites its paths relative to the project root, and records a structured
`stage_result.json`. It also records
the files' SHA-256 so `apply` can check they did not change.

## Build

It is a module of the repository's Maven build; `mvn package` at the root builds
it into one self-contained jar, which the distribution bundles.
