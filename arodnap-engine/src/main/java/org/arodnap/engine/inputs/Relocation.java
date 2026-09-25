package org.arodnap.engine.inputs;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.arodnap.engine.files.FilePaths;
import org.arodnap.model.CompileUnit;

/**
 * Moves compile units from the project to its copy. The Maven and Gradle plugins read their build's
 * model, whose paths point into the project; the engine analyzes and repairs the copy, so every path
 * inside the project must point to the same file in the copy. Paths outside the project (dependency
 * jars in a repository cache) stay as they are.
 */
public final class Relocation {
    private final Path project;
    private final Path copy;

    public Relocation(Path project, Path copy) {
        this.project = FilePaths.real(project);
        this.copy = FilePaths.real(copy);
    }

    public Path relocate(Path path) {
        Path resolved = FilePaths.real(path);
        return resolved.startsWith(project) ? copy.resolve(project.relativize(resolved).toString()) : resolved;
    }

    public CompileUnit relocate(CompileUnit unit) {
        return new CompileUnit(relocate(unit.workingDirectory()), relocateAll(unit.sources()), relocateAll(unit.classpath()),
                unit.outputDirectory().map(this::relocate), unit.generatedSourceDirectory().map(this::relocate), unit.release(), unit.encoding(),
                relocateAll(unit.processorPath()), unit.processors(), unit.label());
    }

    public List<CompileUnit> relocateUnits(List<CompileUnit> units) {
        return units.stream().map(this::relocate).toList();
    }

    private List<Path> relocateAll(List<Path> paths) {
        return paths.stream().map(this::relocate).toList();
    }

    /** Helper for front ends: an optional path, relocated. */
    public Optional<Path> relocate(Optional<Path> path) {
        return path.map(this::relocate);
    }
}
