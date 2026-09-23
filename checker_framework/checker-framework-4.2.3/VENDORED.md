# Vendored Checker Framework 4.2.3

Trimmed copy of the official release
`https://github.com/typetools/checker-framework/releases/download/checker-framework-4.2.3/checker-framework-4.2.3.zip`
(published 2026-09-01, zip SHA-256
`20e6b169ab6552f44d645d896b3379a840b5daf4310c99ea238750572fc78634`). Only what Arodnap runs is kept: `LICENSE.txt`,
`checker/bin/` (including do-like-javac) and `checker/dist/checker.jar`,
`checker-qual.jar` and `checker-util.jar`. The docs, annotation-file-utilities
and the javadoc/source jars are omitted.

To use another Checker Framework distribution, pass `--checker-framework <dir>`
or set `ARODNAP_CHECKER_FRAMEWORK`.
