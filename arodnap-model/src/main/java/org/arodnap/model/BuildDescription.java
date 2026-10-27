package org.arodnap.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * How a front end learned the project's inputs, for the reports.
 *
 * @param buildSystem {@code gradle}, {@code maven}, {@code ant} or {@code command}
 * @param adapterName which front end mechanism was used (e.g. {@code maven} for the CLI's Maven
 *     recording, {@code maven-plugin} for the Maven plugin)
 * @param buildFile the build file (or the project directory for a plain command)
 * @param buildTool the build program, e.g. {@code ["./gradlew"]}
 * @param buildToolSource {@code wrapper}, {@code system}, {@code command} or {@code plugin}
 * @param compileTarget the build target or goal that compiled the sources, if one was chosen
 * @param buildCommand the whole command that ran, empty when no command ran (the plugins)
 */
public record BuildDescription(
        String buildSystem,
        String adapterName,
        Path buildFile,
        List<String> buildTool,
        String buildToolSource,
        String compileTarget,
        List<String> buildCommand) {

    public BuildDescription {
        Objects.requireNonNull(buildSystem, "buildSystem");
        Objects.requireNonNull(adapterName, "adapterName");
        Objects.requireNonNull(buildFile, "buildFile");
        buildTool = List.copyOf(buildTool);
        Objects.requireNonNull(buildToolSource, "buildToolSource");
        Objects.requireNonNull(compileTarget, "compileTarget");
        buildCommand = List.copyOf(buildCommand);
    }
}
