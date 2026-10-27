package org.arodnap.engine.pipeline;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * What the user asked for.
 *
 * @param command {@code analyze}, {@code infer}, {@code repair}, {@code apply} or {@code doctor}
 * @param repoRoot the project, which only {@code apply} changes
 * @param outDir where the reports, logs and patches go
 * @param keepWorkspace keep the copy of the project after the run, for debugging
 * @param timeouts per-command limits
 * @param fieldTransformations which fields the field transformations may change
 * @param buildArgs extra build arguments, as the front end received them (for the reports)
 * @param compileTarget the build target that compiles the sources, if the user chose one
 */
public record RunSettings(
        String command,
        Path repoRoot,
        Path outDir,
        boolean keepWorkspace,
        Timeouts timeouts,
        FieldTransformationMode fieldTransformations,
        List<String> buildArgs,
        Optional<String> compileTarget) {

    public RunSettings {
        Objects.requireNonNull(command, "command");
        repoRoot = repoRoot.toAbsolutePath().normalize();
        outDir = outDir.toAbsolutePath().normalize();
        Objects.requireNonNull(timeouts, "timeouts");
        Objects.requireNonNull(fieldTransformations, "fieldTransformations");
        buildArgs = List.copyOf(buildArgs);
        Objects.requireNonNull(compileTarget, "compileTarget");
    }

    /** Which private fields the field transformations may make final or local. */
    public enum FieldTransformationMode {
        /** Only fields that can hold a resource (the default). */
        RESOURCES,
        /** Every eligible field, as in the paper. */
        ALL,
        /** None. */
        OFF;

        public String cliName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static FieldTransformationMode fromCliName(String name) {
            return valueOf(name.toUpperCase(Locale.ROOT));
        }
    }
}
