# Java Subtools

The Java subprojects behind Arodnap's repair pipeline. The Python CLI runs the
jars in `prebuilt_plugin_jars/`.

| Source project | Prebuilt jar | Pipeline role |
| -------------- | ------------ | ------------- |
| `FieldTransformations/` | `arodnap-field-transformations.jar` | `field_transformations` stage: Error Prone checks `ResourceFieldCanBeFinal` and `ResourceFieldCanBeLocal` |
| `AutoCloseInjector/` | `AutoCloseInjector-1.0-SNAPSHOT.jar` | `close_injector` stage |
| `OwningFieldFixer/` | `OwningFieldFixer-1.0-SNAPSHOT.jar` | `owning_field` stage |
| `RLPatcher/` | `RLPatcher-1.0-SNAPSHOT.jar` | `rlpatcher` stage |
| `AntCapture/` | `arodnap-ant-capture.jar` | build capture for Ant |
| `MavenCapture/` | `arodnap-maven-capture.jar` | build capture for Maven |

RLFixer lives under `rlfixer/wala/` (Maven build); its jar
`RLFixer-1.0-SNAPSHOT.jar` is also in `prebuilt_plugin_jars/`.

Third-party jars in `prebuilt_plugin_jars/`, used to run the field
transformations:

- `error_prone_core-2.42.0-with-dependencies.jar`: Error Prone 2.42.0, the last
  release that runs on JDK 17
- `dataflow-errorprone-3.41.0-eisop1.jar`: the dataflow library that release uses

The paper's earlier Error Prone plugins and their Error Prone 2.28 jars are in
`legacy/prebuilt_plugin_jars/`.

## Rebuilding

From each subproject root, with JDK 21:

```bash
mvn clean package
```

Then copy the jar from `target/` into `prebuilt_plugin_jars/`.
