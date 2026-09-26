package org.arodnap.engine.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where a run's outputs go, under {@code --out-dir} ({@code arodnap-out} by default):
 * {@code report.json}, {@code report.html}, {@code manifest.json}, {@code diagnostics/},
 * {@code inference/}, {@code logs/}, {@code stages/} and the verified patch in {@code patches/}.
 */
public record OutputLayout(Path root) {
    public OutputLayout {
        root = root.toAbsolutePath().normalize();
    }

    public Path report() {
        return root.resolve("report.json");
    }

    public Path htmlReport() {
        return root.resolve("report.html");
    }

    public Path manifest() {
        return root.resolve("manifest.json");
    }

    public Path diagnosticsDirectory() {
        return root.resolve("diagnostics");
    }

    public Path inferenceDirectory() {
        return root.resolve("inference");
    }

    public Path logsDirectory() {
        return root.resolve("logs");
    }

    public Path stagesDirectory() {
        return root.resolve("stages");
    }

    public Path patchesDirectory() {
        return root.resolve("patches");
    }

    public Path patchesManifest() {
        return patchesDirectory().resolve("manifest.json");
    }

    public Path stage(String name) {
        return stagesDirectory().resolve(name);
    }

    public void create() throws IOException {
        for (Path directory : new Path[] {root, diagnosticsDirectory(), inferenceDirectory(), logsDirectory(), stagesDirectory(),
                patchesDirectory()}) {
            Files.createDirectories(directory);
        }
    }

    /** The files of one analysis, by its label ({@code initial}, {@code post_close_injector}, ..., {@code final}). */
    public AnalysisFiles analysis(String label) {
        Path logs = logsDirectory().resolve(label);
        return new AnalysisFiles(label, logs, logs.resolve("wpi.log"), inferenceDirectory().resolve(label),
                diagnosticsDirectory().resolve(label + ".txt"), logs.resolve("source-files.txt"), logs.resolve("app-classes.txt"),
                logs.resolve("classpath-entries.txt"), logs.resolve("adapter-metadata.json"));
    }

    /** The files one analysis writes. */
    public record AnalysisFiles(
            String label,
            Path logsDirectory,
            Path wpiLog,
            Path inferenceDirectory,
            Path diagnostics,
            Path sourceFiles,
            Path appClasses,
            Path classpathEntries,
            Path adapterMetadata) {
        public void create() throws IOException {
            Files.createDirectories(logsDirectory);
            Files.createDirectories(inferenceDirectory.getParent());
            Files.createDirectories(diagnostics.getParent());
        }
    }
}
