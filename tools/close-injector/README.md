# AutoCloseInjector

`AutoCloseInjector` rewrites classes that hold closeable resources but do not
expose the close operation needed by later repairs. The engine runs it in the
`close_injector` stage, normalizes the patch it writes, and applies it to the
workspace copy only.

## Inputs And Outputs

```bash
java -jar target/arodnap-close-injector-<version>.jar <warning-file> <project-root>
```

- reads Checker Framework diagnostics from `<warning-file>`
- analyzes source files under `<project-root>`, or the files listed in
  `-Darodnap.sourcesFile` (with `-Darodnap.classpathFile`, `-Darodnap.release`
  and `-Darodnap.encoding`), which the engine always passes
- writes a combined patch to `-Darodnap.patchFile`, or else to
  `<project-root>/src/java-parser-AutoCloseInjector.patch`

The engine copies the patch into `stages/close_injector/` of the output directory,
rewrites its paths relative to the project root, and records a structured
`stage_result.json`.

## Build

It is a module of the repository's Maven build; `mvn package` at the root builds
it into one self-contained jar, which the distribution bundles.
