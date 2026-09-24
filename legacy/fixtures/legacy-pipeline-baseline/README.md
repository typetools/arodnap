# Legacy Pipeline Baseline

This fixture is a normalized copy of `tests/fixtures/gradle-pipeline-baseline`
for use with the current legacy Arodnap pipeline.

It follows the old layout expected by the existing scripts:

- `src/`
- `lib/`
- `info/sources`
- `info/classes`
- `jarfile/`

The sources intentionally mirror the Gradle fixture so we can compare legacy and
modernized behavior against the same baseline cases.

Do not hand-edit the Java sources here unless the Gradle fixture is updated to
match. Regenerate this fixture with:

```bash
python3 tests/fixtures/sync_legacy_fixture.py
```
