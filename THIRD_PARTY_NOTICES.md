# Third-party software

Arodnap's own code is under the MIT License (see `LICENSE`). The Arodnap
distribution (the command line) also bundles the following third-party software,
each under its own license; the Maven and Gradle plugins download the same
artifacts from Maven repositories instead. The tools run as separate programs;
Arodnap does not link with them.

| Component | Where it is in the distribution | License |
|---|---|---|
| [Checker Framework](https://checkerframework.org/) 4.2.3 | `checker-framework/` | GPL-2.0 with Classpath Exception; annotations (checker-qual) and `.astub` files MIT. See the project's [LICENSE.txt](https://github.com/typetools/checker-framework/blob/master/LICENSE.txt) |
| [EISOP dataflow for Error Prone](https://github.com/eisop/checker-framework) 3.41.0-eisop1 | `tools/dataflow-errorprone-3.41.0-eisop1.jar` | GPL-2.0 with Classpath Exception |
| [Error Prone](https://errorprone.info/) 2.42.0 and 2.50.0, with the libraries in its release jar | `tools/error_prone_core-*-with-dependencies.jar` | Apache-2.0; bundled libraries include Guava, Caffeine, google-java-format, java-diff-utils, AutoValue (Apache-2.0), Protocol Buffers (BSD-3-Clause) and PCollections (MIT). See `META-INF/` in the jar |
| [WALA](https://github.com/wala/WALA) 1.8.0 | inside `tools/arodnap-rlfixer-*.jar` | EPL-2.0 |
| [JavaParser](https://javaparser.org/) | inside the `arodnap-close-injector`, `arodnap-owning-field-fixer`, `arodnap-rlfixer` and `arodnap-rlpatcher` jars in `tools/` | Apache-2.0 or LGPL-3.0 (at your option) |
| [Javassist](https://www.javassist.org/) | inside `tools/arodnap-rlfixer-*.jar` | MPL-1.1, LGPL-2.1 or Apache-2.0 (at your option) |
| [Guava](https://github.com/google/guava) | inside `tools/arodnap-rlfixer-*.jar` | Apache-2.0 |
| [Gson](https://github.com/google/gson) | inside the `arodnap-close-injector`, `arodnap-rlfixer` and `arodnap-rlpatcher` jars in `tools/` | Apache-2.0 |
| [Jackson](https://github.com/FasterXML/jackson) | `lib/jackson-*.jar` | Apache-2.0 |
| [picocli](https://picocli.info/) | `lib/picocli-*.jar` | Apache-2.0 |
