# Third-party software

Arodnap's own code is under the MIT License (see `LICENSE`). The Arodnap package
also bundles the following third-party software, each under its own license.
The bundled tools run as separate programs; Arodnap does not link with them.

| Component | Where it is bundled | License |
|---|---|---|
| [Checker Framework](https://checkerframework.org/) 4.2.3 | `checker-framework-4.2.3/` | GPL-2.0 with Classpath Exception; annotations (checker-qual) and `.astub` files MIT. Full text in `checker-framework-4.2.3/LICENSE.txt` |
| [EISOP dataflow for Error Prone](https://github.com/eisop/checker-framework) 3.41.0-eisop1 | `jars/dataflow-errorprone-3.41.0-eisop1.jar` | GPL-2.0 with Classpath Exception |
| [Error Prone](https://errorprone.info/) 2.42.0 and 2.50.0, with the libraries in its release jar | `jars/error_prone_core-*-with-dependencies.jar` | Apache-2.0; bundled libraries include Guava, Caffeine, google-java-format, java-diff-utils, AutoValue (Apache-2.0), Protocol Buffers (BSD-3-Clause) and PCollections (MIT). See `META-INF/` in the jar |
| [WALA](https://github.com/wala/WALA) 1.8.0 | inside `jars/RLFixer-1.0-SNAPSHOT.jar` | EPL-2.0 |
| [JavaParser](https://javaparser.org/) | inside the `AutoCloseInjector`, `OwningFieldFixer`, `RLFixer` and `RLPatcher` jars | Apache-2.0 or LGPL-3.0 (at your option) |
| [Javassist](https://www.javassist.org/) | inside `jars/RLFixer-1.0-SNAPSHOT.jar` | MPL-1.1, LGPL-2.1 or Apache-2.0 (at your option) |
| [Guava](https://github.com/google/guava) | inside `jars/RLFixer-1.0-SNAPSHOT.jar` | Apache-2.0 |
| [Gson](https://github.com/google/gson) | inside the `AutoCloseInjector`, `RLFixer` and `RLPatcher` jars | Apache-2.0 |

Paths are relative to `arodnap/_bundled/` in the installed package, or to
`restructure_plugins/prebuilt_plugin_jars/` and `checker_framework/` in the
source repository.
