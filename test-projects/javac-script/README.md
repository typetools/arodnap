# Javac Script

No build tool: `build.sh` calls `javac` directly. Arodnap captures it with
`arodnap repair <repo> -- ./build.sh`, putting a recording javac first on PATH.
