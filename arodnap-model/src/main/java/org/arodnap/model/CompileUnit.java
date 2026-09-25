package org.arodnap.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One {@code javac} invocation of the project's build: what it compiled and how.
 *
 * @param workingDirectory the directory javac ran in; relative paths are resolved against it
 * @param sources the source files it compiled
 * @param classpath its class path
 * @param outputDirectory where it wrote class files ({@code -d}), if given
 * @param generatedSourceDirectory where annotation processors wrote sources ({@code -s}), if given
 * @param release the Java release level ({@code --release}, else {@code -source}), if given
 * @param encoding the source encoding ({@code -encoding}), if given
 * @param label which build step made the call, for diagnostics (e.g. a Gradle task path)
 */
public record CompileUnit(
        Path workingDirectory,
        List<Path> sources,
        List<Path> classpath,
        Optional<Path> outputDirectory,
        Optional<Path> generatedSourceDirectory,
        Optional<Integer> release,
        Optional<String> encoding,
        Optional<String> label) {

    public CompileUnit {
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        sources = List.copyOf(sources);
        classpath = List.copyOf(classpath);
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(generatedSourceDirectory, "generatedSourceDirectory");
        Objects.requireNonNull(release, "release");
        Objects.requireNonNull(encoding, "encoding");
        Objects.requireNonNull(label, "label");
    }
}
