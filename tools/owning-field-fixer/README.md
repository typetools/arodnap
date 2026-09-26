# OwningFieldFixer

`OwningFieldFixer` handles owning-field reassignment warnings. The engine runs it
in the `owning_field` stage, normalizes the patch it writes, and applies it to the
workspace copy only.

## Inputs And Outputs

```bash
java -jar target/arodnap-owning-field-fixer-<version>.jar --log /path/to/diagnostics.txt --project-root /path/to/project
```

- reads Resource Leak Checker diagnostics from `--log`
- analyzes source files under `--project-root`, or the files listed in
  `-Darodnap.sourcesFile` (with `-Darodnap.classpathFile`, `-Darodnap.release`
  and `-Darodnap.encoding`), which the engine always passes
- for each field, tries making it `private` and `final` together, then each alone,
  and keeps the first change that removes the warning
- writes a patch to `-Darodnap.patchFile`, or else to
  `<project-root>/src/owning-field.patch`

The engine copies the patch into `stages/owning_field/` of the output directory,
rewrites its paths relative to the project root, and records a structured
`stage_result.json`.

## Build

It is a module of the repository's Maven build; `mvn package` at the root builds
it into one self-contained jar, which the distribution bundles.
