# FieldCanBeLocalWithTryCatch (Error Prone Plugin)

This is a custom Error Prone plugin that extends the standard `FieldCanBeLocal` check by adding support for analyzing fields even when they are used inside `try-catch` blocks.  

Specifically, it identifies private fields that can safely be converted to local variables — including cases where assignments appear inside exception handling — and generates automatic fix suggestions.


### Notes

- This plugin integrates seamlessly with Error Prone’s patching system.
- It can be used in patching mode to automatically rewrite code based on detected improvements.
- For full details on how to invoke Error Prone plugins and apply patches, refer to the official documentation:  
  https://errorprone.info/docs/patching  
  https://errorprone.info/docs/flags
