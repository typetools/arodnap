# Maven Dependency Leak

A single-module Maven project whose leak goes through a Maven Central dependency
(commons-io). Arodnap captures it by forking the compiler plugin to a recording javac.
