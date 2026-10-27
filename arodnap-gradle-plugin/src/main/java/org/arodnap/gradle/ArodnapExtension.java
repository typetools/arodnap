package org.arodnap.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;

/** The {@code arodnap { }} block. */
public interface ArodnapExtension {
    /** Where reports, logs and the patch go; {@code build/arodnap} by default. */
    DirectoryProperty getOutputDirectory();

    /** {@code resources} (the default), {@code all} or {@code off}: which private fields may be made final or local. */
    Property<String> getFieldTransformations();

    /** Keep the copy of the build Arodnap analyzes, for debugging. */
    Property<Boolean> getKeepWorkspace();

    /** Limit in seconds for each Checker Framework run; none by default. */
    Property<Integer> getAnalysisTimeout();

    /** Limit in seconds for each repair tool run; none by default. */
    Property<Integer> getStageTimeout();
}
