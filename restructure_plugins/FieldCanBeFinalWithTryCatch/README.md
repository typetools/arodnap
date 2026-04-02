# FieldCanBeFinalWithTryCatch

Status: internal / experimental. This plugin is not part of the public
`arodnap analyze|infer|repair|apply` workflow.

This Maven subproject contains a custom Error Prone rule for exploring
`FieldCanBeFinal` behavior in the presence of `try` / `catch` control flow. It is
kept in the repository for research and iteration, not as a supported Arodnap
stage.

If you work on it directly:

- build it with `mvn clean package`
- refresh `../prebuilt_plugin_jars/FieldCanBeFinalWithTryCatch-1.0-SNAPSHOT.jar`
  only if you intentionally want to update the internal experimental artifact

The prebuilt Error Prone dependency jars under `../prebuilt_plugin_jars/` support
this module, but they are not standalone Arodnap commands.
