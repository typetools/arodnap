# Ant Vendored Jar

An Ant project that compiles against a jar checked into `lib/` (built from a one-class
`vendor.Bytes` library). Arodnap captures it with a recording Ant compiler adapter and must
keep the vendored jar on the analysis classpath.
