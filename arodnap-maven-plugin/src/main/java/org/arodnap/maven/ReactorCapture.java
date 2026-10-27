package org.arodnap.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.arodnap.engine.inputs.CompileUnits;
import org.arodnap.engine.inputs.ProjectCapture;
import org.arodnap.engine.inputs.Relocation;
import org.arodnap.engine.inputs.UnsupportedProjectException;
import org.arodnap.model.BuildDescription;
import org.arodnap.model.CompileUnit;

/**
 * The inputs of every module of the reactor, read from Maven's model after the modules compiled.
 * The build does not run again: the model already has the sources, class paths and compiler
 * settings. Paths in the project are moved to the engine's copy of it.
 */
final class ReactorCapture implements ProjectCapture {
    private final MavenSession session;
    private final List<MavenProject> projects;
    private final Path root;

    ReactorCapture(MavenSession session, List<MavenProject> projects, Path root) {
        this.session = session;
        this.projects = List.copyOf(projects);
        this.root = root;
    }

    @Override
    public BuildDescription detect(Path projectRoot) {
        return new BuildDescription("maven", "maven-plugin", projectRoot.resolve("pom.xml"), List.of("mvn"), "plugin", "compile", List.of());
    }

    @Override
    public CapturedProject capture(Path workspaceRoot, Path stateDirectory) throws UnsupportedProjectException {
        List<CompileUnit> units = new ArrayList<>();
        for (MavenProject project : projects) {
            try {
                List<CompileUnit> moduleUnits = MavenCompileUnits.of(project, session);
                if (!moduleUnits.isEmpty() && !moduleUnits.get(0).outputDirectory().map(Files::isDirectory).orElse(false)) {
                    throw new UnsupportedProjectException("Module " + project.getArtifactId() + " is not compiled. Run the goal after "
                            + "compiling, e.g. `mvn compile arodnap:repair`.");
                }
                units.addAll(moduleUnits);
            } catch (IOException | DependencyResolutionRequiredException e) {
                throw new UnsupportedProjectException("Cannot read the compilation of module " + project.getArtifactId() + ": " + e.getMessage(), e);
            }
        }
        List<CompileUnit> relocated = new Relocation(root, workspaceRoot).relocateUnits(units);
        return new CapturedProject(CompileUnits.merge(relocated), detect(workspaceRoot), List.of());
    }
}
