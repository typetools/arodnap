# Javac Generated Source

`build.sh` generates `gen/demo/Generated.java` from a template, then compiles it with
`src/`, the way a parser generator's output is compiled. Both classes leak the same way.
`gen/` is also in the project, as it is after a build, so only knowing that the build writes
it keeps it out of the patch: `src/demo/FirstByte.java` is repaired, `Generated.java` is
reported with the reason `generated` and left alone.
