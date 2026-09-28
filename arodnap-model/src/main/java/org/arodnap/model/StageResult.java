package org.arodnap.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What one pipeline stage did. Written to {@code stages/<stage>/stage_result.json}; the field names
 * are part of the stable output contract.
 *
 * @param stage the stage name, e.g. {@code close_injector}
 * @param changed whether the stage changed any source file
 * @param changedFiles repository-relative paths of the files it changed, sorted
 * @param rerunRequired whether the analysis must run again before the next stage
 * @param artifacts named files the stage produced (log, patch, manifest, ...), as paths
 * @param notes short human-readable remarks
 * @param success whether the stage completed; a failed stage stops the run
 */
public record StageResult(
        String stage,
        boolean changed,
        List<String> changedFiles,
        boolean rerunRequired,
        Map<String, String> artifacts,
        List<String> notes,
        boolean success) {

    public StageResult {
        Objects.requireNonNull(stage, "stage");
        changedFiles = List.copyOf(changedFiles);
        // Keeps insertion order: report and stage_result.json list artifacts in the order written.
        artifacts = Collections.unmodifiableMap(new LinkedHashMap<>(artifacts));
        notes = List.copyOf(notes);
    }
}
