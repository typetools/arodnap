package org.arodnap.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What Arodnap analyzes: every compile unit of the build, compiled together as one program.
 *
 * <p>This is the only thing a front end (the CLI's build recording, the Maven plugin, the Gradle
 * plugin) has to produce. The engine never needs to know which build tool made it.
 *
 * @param units the build's compile units, for diagnostics
 * @param sources the source files in the repository, analyzed and possibly repaired
 * @param generatedSources sources generated during the build, analyzed but never patched
 * @param classpath dependencies, without anything the build compiled from {@code sources}
 * @param release the highest Java release level of the units, if any unit gave one
 * @param encoding the source encoding, if the build gave one
 * @param sourceRoot the common root of the sources, which RLFixer needs as its project directory
 */
public record ProjectInputs(
        List<CompileUnit> units,
        List<Path> sources,
        List<Path> generatedSources,
        List<Path> classpath,
        Optional<Integer> release,
        Optional<String> encoding,
        Path sourceRoot) {

    public ProjectInputs {
        units = List.copyOf(units);
        sources = List.copyOf(sources);
        generatedSources = List.copyOf(generatedSources);
        classpath = List.copyOf(classpath);
        Objects.requireNonNull(release, "release");
        Objects.requireNonNull(encoding, "encoding");
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("A project needs at least one source file.");
        }
    }
}
