# AutoCloseInjector

Status: shipping stage in the current Arodnap repair workflow.

`AutoCloseInjector` rewrites classes that hold closeable resources but do not
expose the close operation needed by later repairs. The public Python pipeline
invokes this jar during the `close_injector` stage, normalizes the emitted patch,
and applies it to the temporary workspace copy only.

## Inputs And Outputs

Direct invocation expects:

```bash
java -jar target/AutoCloseInjector-1.0-SNAPSHOT.jar <warning-file> <project-root>
```

Current tool behavior:

- reads Checker Framework diagnostics from `<warning-file>`
- analyzes source files under `<project-root>`
- emits a combined patch at `<project-root>/src/java-parser-AutoCloseInjector.patch`

The public Arodnap CLI does not expose that raw patch path. The Python stage
wrapper copies the patch into `arodnap-out/stages/close_injector/`, rewrites the
paths relative to the repo root, applies it to the workspace copy, and records a
structured `stage_result.json`.

## Rebuild

```bash
mvn clean package
```

Refresh the shipping jar after rebuilding:

```bash
cp target/AutoCloseInjector-1.0-SNAPSHOT.jar ../prebuilt_plugin_jars/
```
