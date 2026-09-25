package org.arodnap.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.arodnap.engine.apply.BundleApplier;

/**
 * Applies the patch {@code arodnap:repair} wrote to the project. Every changed file must still be
 * exactly what the patch was made from; the patch is tried on a copy first.
 */
@Mojo(name = "apply", aggregator = true, threadSafe = true)
public class ApplyMojo extends ArodnapMojo {
    /** The patch directory; defaults to {@code patches} in the output directory. */
    @Parameter(property = "arodnap.patchDirectory")
    private File patchDirectory;

    @Override
    String command() {
        return "apply";
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping Arodnap (arodnap.skip).");
            return;
        }
        Path patches = patchDirectory != null ? patchDirectory.toPath().toAbsolutePath().normalize() : output().resolve("patches");
        try {
            BundleApplier.apply(projectRoot(), patches, output().resolve("logs").resolve("apply.log"), keepWorkspace);
            getLog().info("Applied the patch in " + patches + " to " + projectRoot() + ".");
        } catch (BundleApplier.ApplyException e) {
            throw new MojoFailureException("Arodnap could not apply the patch: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException("Arodnap could not apply the patch: " + e.getMessage(), e);
        }
    }
}
