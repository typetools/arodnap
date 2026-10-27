# Gradle Multi-Module

Two modules: `app` depends on `core` and on commons-io, with a leak in each module.
All modules are analyzed together; `core`'s jar on `app`'s classpath is a build
artifact and is replaced by `core`'s sources.
