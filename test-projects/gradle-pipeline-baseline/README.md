# Gradle Pipeline Baseline

This fixture is a single-module Gradle Java project intended to exercise the
main Arodnap pipeline while the repo is being modernized.

It is intentionally small and self-contained. The sources are designed to give
the pipeline a stable baseline for:

- direct local resource leaks
- try/catch-local leaks
- wrapper classes that should be repaired by the close injector
- non-final owning field reassignment for the owning-field fixer

## Intended Cases

- `DirectLeakExample`
  - Simple local leak from a `BufferedReader`.
  - Baseline case for RLC, RLFixer, and RLPatcher.

- `TryCatchLeakExample`
  - Local leak created inside a `try` block with no `finally`.
  - Useful for the try/catch-oriented RLFixer path.

- `WrapperMissingClose`
  - Resource wrapper with a field assigned in the constructor and no `close()`
    method.
  - Intended close-injector target.

- `OwningFieldReassignment`
  - Non-final `@Owning` field reassigned without closing the old resource.
  - Intended owning-field fixer target.

## Notes

- The fixture compiles as a normal Gradle project.
- The owning-field case uses a local `compileOnly` dependency on the vendored
  `checker-qual.jar` so it can compile offline within this repository.
- The project does not need to run successfully at runtime; it only needs to
  compile and present stable analysis targets.
