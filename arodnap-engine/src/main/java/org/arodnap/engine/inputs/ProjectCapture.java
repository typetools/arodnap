package org.arodnap.engine.inputs;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.arodnap.model.BuildDescription;
import org.arodnap.model.ProjectInputs;

/**
 * What a front end implements: finding out what to analyze. The CLI records the project's own
 * build; the Maven and Gradle plugins read their build's model. Nothing else in the engine knows
 * which build tool is involved.
 */
public interface ProjectCapture {
    /**
     * What kind of build the project has, without running it. Throws when there is nothing this front
     * end can capture, so a run can stop before copying the project.
     */
    BuildDescription detect(Path projectRoot) throws UnsupportedProjectException;

    /**
     * The inputs of the copy of the project at {@code workspaceRoot}. Every path in the result must be
     * in the copy (or outside the project, like dependency jars), never in the original project.
     *
     * @param stateDirectory a directory next to the copy for files the capture needs (it is not
     *     part of the project, so the project's build never sees them)
     */
    CapturedProject capture(Path workspaceRoot, Path stateDirectory) throws UnsupportedProjectException;

    /**
     * A captured project.
     *
     * @param inputs what to analyze
     * @param build how it was learned
     * @param logs files to keep with the analysis logs (the recorded javac calls, the build log)
     */
    record CapturedProject(ProjectInputs inputs, BuildDescription build, List<Path> logs) {
        public CapturedProject {
            Objects.requireNonNull(inputs, "inputs");
            Objects.requireNonNull(build, "build");
            logs = List.copyOf(logs);
        }
    }
}
