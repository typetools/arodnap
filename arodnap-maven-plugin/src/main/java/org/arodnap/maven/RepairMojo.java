package org.arodnap.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.arodnap.engine.pipeline.RunFailedException;
import org.arodnap.engine.pipeline.RunSettings.FieldTransformationMode;

/**
 * Repairs resource leaks in every module of the build, on a copy of the project, and writes one
 * verified patch to {@code target/arodnap/patches}. The project is not changed; apply the patch with
 * {@code arodnap:apply}. Run it after compiling: {@code mvn compile arodnap:repair}.
 */
@Mojo(name = "repair", aggregator = true, requiresDependencyResolution = ResolutionScope.COMPILE, threadSafe = true)
public class RepairMojo extends ArodnapMojo {
    /**
     * Which private fields may be made final or local before the analysis: {@code resources} (fields
     * that can hold a resource, the default), {@code all} (every eligible field, as in the paper) or
     * {@code off}.
     */
    @Parameter(property = "arodnap.fieldTransformations", defaultValue = "resources")
    private String fieldTransformations;

    @Override
    String command() {
        return "repair";
    }

    @Override
    FieldTransformationMode fieldTransformations() {
        return FieldTransformationMode.fromCliName(fieldTransformations);
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping Arodnap (arodnap.skip).");
            return;
        }
        if (!java.util.List.of("resources", "all", "off").contains(fieldTransformations)) {
            throw new MojoExecutionException("fieldTransformations: expected resources, all or off, got " + fieldTransformations);
        }
        try {
            engine(toolchain(), log()).repair(settings(), capture());
        } catch (RunFailedException e) {
            throw failure(e, output().resolve("report.json"));
        }
    }
}
