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

## What Arodnap uses from it

- `checker/dist/checker.jar` (with `checker-qual.jar` and `checker-util.jar`):
  the Resource Leak Checker, run as `java -jar checker.jar` on the resolved JDK.
- Nothing from `checker/bin/` at run time. Whole-program inference is Arodnap's
  own loop (`arodnap/analysis/wpi_runner.py`). `bin/` is kept because
  `wpi.sh` lists the JDKs upstream tests on (used for a `doctor` warning) and
  do-like-javac's `tools/wpi.py` is the loop Arodnap mirrors.

## Upgrading

1. Download the new release zip, record its URL and SHA-256 here, and copy the
   same subset into `checker_framework/checker-framework-<version>/`.
2. Point `VENDORED_CHECKER_FRAMEWORK` in `arodnap/orchestrator/config.py` at it.
3. Run `python -m unittest discover -s tests -p "test_*.py"`. If
   `tests/test_wpi_upstream_parity.py` fails, do-like-javac's WPI loop changed:
   diff its `tools/wpi.py` against the previous version, port relevant changes
   into `wpi_runner.py`, then update the pinned hash.
4. Run `ARODNAP_E2E=1 python -m unittest tests.test_e2e_real` and compare the
   repair results with the previous version (warning counts and
   `patches/arodnap.patch` should match unless the checker itself changed).
