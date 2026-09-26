# javac-field-transformations

Exercises the field transformations that run before the first analysis: a wrapper field
assigned in its constructor becomes `final`, a socket assigned inside `try` becomes `final` with
a temporary, a reader used only inside one method becomes a local variable, and a plain `String`
field is left alone (not a resource).
