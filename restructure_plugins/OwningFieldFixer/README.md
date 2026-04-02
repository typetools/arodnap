# OwningFieldFixer

Status: shipping stage in the current Arodnap repair workflow.

`OwningFieldFixer` handles owning-field reassignment warnings. The public Python
pipeline invokes this jar during the `owning_field` stage, normalizes the emitted
patch, and applies it to the temporary workspace copy only.

## Inputs And Outputs

Direct invocation expects:

```bash
java -jar target/OwningFieldFixer-1.0-SNAPSHOT.jar --log /path/to/diagnostics.txt --project-root /path/to/project
```

Current tool behavior:

- reads RLC diagnostics from `--log`
- analyzes source files under `--project-root`
- emits a patch at `<project-root>/src/owning-field.patch`

The public Arodnap CLI does not expose that raw patch file. The Python stage
wrapper copies it into `arodnap-out/stages/owning_field/`, rewrites the paths
relative to the repo root, applies it to the workspace copy, and records a
structured `stage_result.json`.

## Rebuild

```bash
mvn clean package
```

Refresh the shipping jar after rebuilding:

```bash
cp target/OwningFieldFixer-1.0-SNAPSHOT.jar ../prebuilt_plugin_jars/
```
