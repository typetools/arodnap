# RLPatcher

Status: shipping stage in the current Arodnap repair workflow.

`RLPatcher` turns RLFixer suggestions into concrete source patches. The public
Python pipeline invokes this jar during the `rlpatcher` stage, normalizes the
result into repo-root-relative patches, and emits `patches/manifest.json` for the
later `apply` command.

## Inputs And Outputs

Direct invocation expects:

```bash
java -jar target/RLPatcher-1.0-SNAPSHOT.jar --prompt /path/to/prompt.json --project-root /path/to/project
```

Current tool behavior:

- reads prompt data produced from matched RLFixer suggestions and CF warnings
- analyzes source files under `--project-root`
- writes a unified diff named `rlfixer.patch` in the current working directory

The public Arodnap CLI does not expose that raw patch file directly. The Python
stage wrapper copies it into `arodnap-out/stages/rlpatcher/patches/`, rewrites
paths relative to the repo root, computes preimage hashes, and writes the
normalized patch manifest consumed by `arodnap apply`.

## Rebuild

```bash
mvn clean package
```

Refresh the shipping jar after rebuilding:

```bash
cp target/RLPatcher-1.0-SNAPSHOT.jar ../prebuilt_plugin_jars/
```
