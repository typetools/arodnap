# Java Subtools

This directory contains the Java subprojects that back Arodnap's current
shipping repair workflow, plus a small number of internal experimental plugins.
The Python CLI resolves the shipping jars from
`restructure_plugins/prebuilt_plugin_jars/`.

## Shipping Workflow Modules

These source projects are part of the current public Arodnap pipeline:

| Source project | Prebuilt jar | Pipeline role | Status |
| -------------- | ------------ | ------------- | ------ |
| `AutoCloseInjector/` | `prebuilt_plugin_jars/AutoCloseInjector-1.0-SNAPSHOT.jar` | close-injector stage | shipping |
| `OwningFieldFixer/` | `prebuilt_plugin_jars/OwningFieldFixer-1.0-SNAPSHOT.jar` | owning-field stage | shipping |
| `RLPatcher/` | `prebuilt_plugin_jars/RLPatcher-1.0-SNAPSHOT.jar` | RL patch materialization stage | shipping |

The shipping repair pipeline also uses RLFixer, but RLFixer does not live under
`restructure_plugins/`. Its sources and Maven build live under `rlfixer/wala/`,
and the `rlfixer` stage (`arodnap/stages/rlfixer.py`) runs its jar directly.

## Internal Or Experimental Modules

These projects are not part of the public `arodnap analyze|infer|repair|apply`
workflow:

| Source project | Prebuilt jar | Status |
| -------------- | ------------ | ------ |
| `FieldCanBeFinalWithTryCatch/` | `prebuilt_plugin_jars/FieldCanBeFinalWithTryCatch-1.0-SNAPSHOT.jar` | internal / experimental |
| `FieldCanBeLocalWithTryCatch/` | `prebuilt_plugin_jars/FieldCanBeLocalWithTryCatch-1.0-SNAPSHOT.jar` | internal / experimental |

The following jars in `prebuilt_plugin_jars/` are third-party dependencies used
when working on the experimental Error Prone plugins. They are not standalone
Arodnap stages:

- `dataflow-errorprone-3.45.0.jar`
- `error_prone_core-2.28.0-with-dependencies.jar`

## Jar Provenance

Current jar-to-source mapping:

- `AutoCloseInjector-1.0-SNAPSHOT.jar` comes from `restructure_plugins/AutoCloseInjector/`
- `OwningFieldFixer-1.0-SNAPSHOT.jar` comes from `restructure_plugins/OwningFieldFixer/`
- `RLPatcher-1.0-SNAPSHOT.jar` comes from `restructure_plugins/RLPatcher/`
- `FieldCanBeFinalWithTryCatch-1.0-SNAPSHOT.jar` comes from `restructure_plugins/FieldCanBeFinalWithTryCatch/`
- `FieldCanBeLocalWithTryCatch-1.0-SNAPSHOT.jar` comes from `restructure_plugins/FieldCanBeLocalWithTryCatch/`

There is no RLFixer jar in this directory. RLFixer is maintained separately from
these Maven subprojects.

## Rebuilding Shipping Jars

From each subproject root:

```bash
mvn clean package
```

Then refresh the matching prebuilt jar in `prebuilt_plugin_jars/`:

- `AutoCloseInjector/target/AutoCloseInjector-1.0-SNAPSHOT.jar`
- `OwningFieldFixer/target/OwningFieldFixer-1.0-SNAPSHOT.jar`
- `RLPatcher/target/RLPatcher-1.0-SNAPSHOT.jar`

For RLFixer, use the build path under `rlfixer/wala/` instead of this directory.
