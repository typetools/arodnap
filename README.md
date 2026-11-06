# Arodnap

Arodnap finds resource leaks in Java code and repairs them. That includes
leaks in resource wrappers: classes that keep a resource in a field and must
be closed themselves.

It works in three steps:

1. **Find the leaks.** The Checker Framework's Resource Leak Checker reports
   every resource that may not be closed. Arodnap first infers who owns each
   resource in your code.
2. **Make resource lifecycles explicit.** Code transformations make resource
   fields final, or local when a field isn't needed, and give wrapper classes a
   `close()` method. This makes clear who is responsible for closing each
   resource.
3. **Patch.** An escape analysis decides where each resource can safely be
   closed, and Arodnap adds the close there: a `try`-with-resources, a `try` /
   `finally`, or a `close()` call.

The analysis runs again after each step that changes code. The result is a
single patch, checked to apply and compile, for you to review. The paper
[Repairing Leaks in Resource Wrappers](https://arxiv.org/abs/2510.03461)
(ASE 2025) describes each step.

It works with Maven, Gradle (including multi-module builds), Ant, and any other
build that runs `javac`. It analyzes a copy of your project; your code changes
only when you apply the patch.

## Example

This method never closes its stream:

```java
public String readAll(String path) throws IOException {
    FileInputStream in = new FileInputStream(path);
    return IOUtils.toString(in, StandardCharsets.UTF_8);
}
```

`./gradlew arodnapRepair` reports:

```text
Resource leaks: 1 found, 1 fixed, 0 remaining
Patch:  build/arodnap/patches/arodnap.patch (1 file(s))
Apply:  ./gradlew arodnapApply
Report: build/arodnap/report.html
```

The patch:

```diff
     public String readAll(String path) throws IOException {
-        FileInputStream in = new FileInputStream(path);
-        return IOUtils.toString(in, StandardCharsets.UTF_8);
+        try (FileInputStream in = new FileInputStream(path)) {
+            return IOUtils.toString(in, StandardCharsets.UTF_8);
+        }
     }
```

## Getting Started

Arodnap needs JDK 17 or newer, on Linux or macOS. Use it as a Maven plugin, a
Gradle plugin or a command-line tool; all three run the same analysis.

### Maven

Add the plugin to the top-level `pom.xml`:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>io.github.iamsanjaymalakar</groupId>
      <artifactId>arodnap-maven-plugin</artifactId>
      <version>0.1.0</version>
    </plugin>
  </plugins>
</build>
```

It runs only when you call it:

```bash
mvn compile arodnap:repair   # write the patch
mvn arodnap:apply            # apply it to your project
```

To try it without editing the POM, call it by its full name:
`mvn compile io.github.iamsanjaymalakar:arodnap-maven-plugin:0.1.0:repair`.
The other goals and the settings are in [Maven plugin](docs/usage.md#maven-plugin).

### Gradle

Add the plugin to the root project's `build.gradle.kts`. It needs Gradle 8.14 or
newer:

```kotlin
plugins {
    id("io.github.iamsanjaymalakar.arodnap") version "0.1.0"
}

repositories {
    mavenCentral()  // where Arodnap's tools come from
}
```

In a Groovy `build.gradle`, the plugin line is
`id 'io.github.iamsanjaymalakar.arodnap' version '0.1.0'`.

```bash
./gradlew arodnapRepair   # write the patch
./gradlew arodnapApply    # apply it to your project
```

The other tasks, the settings, and builds that declare their repositories in
`settings.gradle(.kts)` are covered in [Gradle plugin](docs/usage.md#gradle-plugin).

### Command line

Download `arodnap-0.1.0-bin.zip` from the
[release](https://github.com/typetools/arodnap/releases/tag/v0.1.0), unzip it,
and put its `bin` directory on your `PATH`:

```bash
unzip arodnap-0.1.0-bin.zip
export PATH="$PWD/arodnap-0.1.0/bin:$PATH"

arodnap repair /path/to/project
arodnap apply --patch-dir ./arodnap-out/patches /path/to/project
```

It runs the project's Gradle, Maven or Ant build. For any other build, pass the
command that compiles it: `arodnap repair /path/to/project -- ./build.sh`.

## Before You Apply The Patch

- `report.html` lists every leak: a fixed one with the change that fixed it, a
  remaining one with the reason Arodnap left it.
- The patch is checked to apply and compile, not to keep the program's
  behavior. Review it, and run your tests after applying it.
- If Arodnap fails on your project, run `doctor` first (`mvn arodnap:doctor`,
  `./gradlew arodnapDoctor` or `arodnap doctor`): it checks the JDK and that
  Arodnap can read your build.

## Limitations

Not supported yet: Lombok, Kotlin and Android sources (Java sources in the same
build are analyzed), builds that compile Java without calling `javac` or a
supported build tool, and `repair` on sources that are not valid UTF-8. Leaks in
code marked `@SuppressWarnings("resource")` and in generated sources are
reported but not repaired. See
[Leaks Arodnap leaves alone](docs/usage.md#leaks-arodnap-leaves-alone).

## Documentation

- [Using Arodnap](docs/usage.md): every command, goal, task and setting, how
  Arodnap reads your build, what it writes, and troubleshooting
- [Architecture](docs/architecture.md), [testing](docs/testing.md) and
  [releasing](docs/releasing.md), for contributors

## Building From Source

```bash
mvn verify
```

builds everything and runs the tests. The end-to-end tests run real builds and
every repair tool, with nothing mocked. They need `gradle`, `mvn` and `ant` on
`PATH`, and are opt-in:

```bash
ARODNAP_E2E=1 mvn install
ARODNAP_E2E=1 gradle -p arodnap-gradle-plugin build
```

To use your own build of Arodnap, see
[Using a build from source](docs/usage.md#using-a-build-from-source).

## License

[MIT](LICENSE)
