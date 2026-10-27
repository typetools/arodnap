# javac-latin1

An older project built by a javac script: its sources are ISO-8859-1 (the Javadoc has
accented characters that are not valid UTF-8) and it compiles for Java 8.

Arodnap must analyze it with the build's `-encoding` and `--release`. `repair` stops
with a clear message, because the repair tools read and write sources as UTF-8.
