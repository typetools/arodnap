package org.arodnap.engine.analysis;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One analysis of the workspace: the Resource Leak Checker's warnings, with or without
 * whole-program inference first.
 *
 * @param label {@code initial}, {@code post_close_injector}, {@code post_owning_field} or {@code final}
 * @param workspaceRoot the copy that was analyzed
 * @param wpiLog the inference log ({@code SKIPPED} for {@code analyze})
 * @param inferenceDirectory the inferred annotations (.ajava files)
 * @param diagnostics the checker's output
 * @param warningCount how many warnings it reported
 * @param sourceFiles the analyzed sources, one per line
 * @param appClasses the program's classes, one per line
 * @param classpathEntries the analysis class path, one entry per line
 * @param adapterMetadata how the inputs were captured, as JSON
 * @param release the Java release level used
 * @param encoding the source encoding used
 * @param inferenceNotes limitations of this analysis, e.g. classes inference could not cover
 */
public record Analysis(
        String label,
        Path workspaceRoot,
        Path wpiLog,
        Path inferenceDirectory,
        Path diagnostics,
        int warningCount,
        Path sourceFiles,
        Path appClasses,
        Path classpathEntries,
        Path adapterMetadata,
        Optional<Integer> release,
        Optional<String> encoding,
        List<String> inferenceNotes) {

    public Analysis {
        inferenceNotes = List.copyOf(inferenceNotes);
    }

    /** As report.json's {@code final_analysis} has it. */
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("workspace_root", workspaceRoot.toString());
        json.put("label", label);
        json.put("wpi_log_path", wpiLog.toString());
        json.put("inference_dir", inferenceDirectory.toString());
        json.put("diagnostics_path", diagnostics.toString());
        json.put("warning_count", warningCount);
        json.put("source_files_file", sourceFiles.toString());
        json.put("app_classes_file", appClasses.toString());
        json.put("classpath_entries_file", classpathEntries.toString());
        json.put("adapter_metadata_path", adapterMetadata.toString());
        json.put("inference_notes", inferenceNotes);
        json.put("release", release.orElse(null));
        json.put("encoding", encoding.orElse(null));
        return json;
    }
}
