package org.arodnap.gradle;

import java.io.IOException;
import java.nio.file.Path;
import org.arodnap.engine.apply.BundleApplier;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;

/**
 * Applies the patch {@code arodnapRepair} wrote to the project. Every changed file must still be
 * exactly what the patch was made from; the patch is tried on a copy first.
 */
public abstract class ApplyTask extends DefaultTask {
    @Internal
    public abstract DirectoryProperty getPatchDirectory();

    @Internal
    public abstract DirectoryProperty getOutputDirectory();

    @Internal
    public abstract DirectoryProperty getProjectDirectory();

    @TaskAction
    public void apply() {
        Path root = getProjectDirectory().get().getAsFile().toPath();
        Path patches = getPatchDirectory().get().getAsFile().toPath();
        try {
            BundleApplier.apply(root, patches, getOutputDirectory().get().getAsFile().toPath().resolve("logs").resolve("apply.log"), false);
            getLogger().lifecycle("Applied the patch in {} to {}.", patches, root);
        } catch (BundleApplier.ApplyException | IOException e) {
            throw new GradleException("Arodnap could not apply the patch: " + e.getMessage(), e);
        }
    }
}
