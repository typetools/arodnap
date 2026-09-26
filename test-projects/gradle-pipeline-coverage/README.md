# Gradle Pipeline Coverage

Makes every repair stage do real work, so the real end-to-end test can check each one:

- `wrapper/`: a resource wrapper without `close()` and a client that leaks it. The close
  injector adds `close()`; whole-program inference then gives the wrapper a must-call
  obligation, so the client's leak appears and RLFixer fixes it (the paper's main scenario).
- `owning/`: non-final, non-private owning fields assigned only in the constructor, which the
  owning-field stage makes `private final`.
- `loop_fixes/`, `param_fixes/`, `return_fixes/`, `throws_fixes/`, `try-catch_fixes/`
  (as `trycatch_fixes`): RLFixer's own strategy test programs from `tools/rlfixer/examples/`, each in
  its own package. `NullableResource.java` declared `public class EscapesTryCatch` upstream;
  it is renamed to match its file here.

Some warnings stay by design: RLFixer marks resources escaping loops as unfixable, and
RLPatcher does not yet materialize RLFixer's "close in the callers" return fixes.
