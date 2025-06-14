# FieldCanBeFinalWithTryCatch (Error Prone Plugin)

This is a custom Error Prone plugin that extends the standard `FieldCanBeFinal` check by adding support for analyzing fields even when assignments occur inside `try-catch` blocks.

The plugin identifies fields that can be safely marked as `final`, even when their assignments are placed inside complex control-flow structures, and generates automatic fix suggestions to apply the `final` modifier or transform the code accordingly.

This plugin integrates smoothly with Error Prone’s patching system and can be used to automatically apply suggested fixes. For details on how to invoke Error Prone plugins and use patching mode, refer to the official documentation:  
https://errorprone.info/docs/patching  
https://errorprone.info/docs/flags