package org.arodnap.engine.report;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.arodnap.engine.Version;
import org.arodnap.engine.analysis.Analysis;
import org.arodnap.engine.json.Json;
import org.arodnap.engine.pipeline.OutputLayout;
import org.arodnap.engine.pipeline.PatchLog;
import org.arodnap.engine.pipeline.RunSettings;
import org.arodnap.engine.stages.StageLog;
import org.arodnap.engine.tools.Toolchain;
import org.arodnap.model.StageResult;

/**
 * Everything a run did, written as {@code manifest.json} and {@code report.json}. Their keys are
 * the stable output contract in docs/architecture.md.
 */
public final class RunRecord {
    private final RunSettings settings;
    private final Toolchain toolchain;
    private final OutputLayout layout;
    private final String startedAt = timestamp();
    private final long startedNanos = System.nanoTime();
    private final List<Map<String, Object>> analysisRuns = new ArrayList<>();
    private final List<Map<String, Object>> stageTimings = new ArrayList<>();
    private final List<StageResult> stageHistory = new ArrayList<>();
    private Path workspaceRoot;
    private String buildSystem = "gradle";
    private String adapterName = "gradle";
    private Analysis currentAnalysis;
    private Path finalPatchManifest;
    private String javaVersion;

    public RunRecord(RunSettings settings, Toolchain toolchain, OutputLayout layout) {
        this.settings = settings;
        this.toolchain = toolchain;
        this.layout = layout;
    }

    public String startedAt() {
        return startedAt;
    }

    public void workspace(Path root) {
        this.workspaceRoot = root;
    }

    /** The build the front end detected, before anything ran. */
    public void build(String system, String adapter) {
        this.buildSystem = system;
        this.adapterName = adapter;
    }

    /** The first line of {@code java -version} for the JDK the run uses. */
    public void javaVersion(String version) {
        this.javaVersion = version;
    }

    public void finalPatchManifest(Path manifest) {
        this.finalPatchManifest = manifest;
    }

    public Analysis currentAnalysis() {
        return currentAnalysis;
    }

    public List<StageResult> stageHistory() {
        return List.copyOf(stageHistory);
    }

    /** Something a run does that can fail with any of the pipeline's checked exceptions. */
    @FunctionalInterface
    public interface Step<T> {
        T run() throws Exception;
    }

    /** Runs an analysis and records its timing and outcome. */
    public Analysis analysis(String label, Step<Analysis> step) throws Exception {
        String started = timestamp();
        long startNanos = System.nanoTime();
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("label", label);
        run.put("started_at", started);
        try {
            Analysis analysis = step.run();
            run.put("completed_at", timestamp());
            run.put("elapsed_seconds", seconds(startNanos));
            run.put("success", true);
            run.put("warning_count", analysis.warningCount());
            run.put("diagnostics_path", analysis.diagnostics().toString());
            run.put("inference_dir", analysis.inferenceDirectory().toString());
            run.put("wpi_log_path", analysis.wpiLog().toString());
            run.put("adapter_metadata_path", analysis.adapterMetadata().toString());
            if (!analysis.inferenceNotes().isEmpty()) {
                run.put("inference_notes", analysis.inferenceNotes());
            }
            analysisRuns.add(run);
            currentAnalysis = analysis;
            return analysis;
        } catch (Exception e) {
            run.put("completed_at", timestamp());
            run.put("elapsed_seconds", seconds(startNanos));
            run.put("success", false);
            run.put("error", e.getMessage());
            run.put("error_type", e.getClass().getSimpleName());
            analysisRuns.add(run);
            throw e;
        }
    }

    /** Runs a stage and records its timing, outcome and result. */
    public StageResult stage(String name, Step<StageResult> step) throws Exception {
        String started = timestamp();
        long startNanos = System.nanoTime();
        Map<String, Object> timing = new LinkedHashMap<>();
        timing.put("stage", name);
        timing.put("started_at", started);
        try {
            StageResult result = step.run();
            timing.put("completed_at", timestamp());
            timing.put("elapsed_seconds", seconds(startNanos));
            timing.put("success", result.success());
            timing.put("changed", result.changed());
            timing.put("rerun_required", result.rerunRequired());
            timing.put("changed_files", result.changedFiles());
            timing.put("artifacts", result.artifacts());
            stageTimings.add(timing);
            stageHistory.add(result);
            return result;
        } catch (Exception e) {
            timing.put("completed_at", timestamp());
            timing.put("elapsed_seconds", seconds(startNanos));
            timing.put("success", false);
            timing.put("error", e.getMessage());
            timing.put("error_type", e.getClass().getSimpleName());
            stageTimings.add(timing);
            throw e;
        }
    }

    /** Writes manifest.json and report.json. */
    public void write(boolean success, Optional<Exception> error, Optional<Map<String, Object>> leaks) throws IOException {
        Map<String, Object> runMetadata = runMetadata();
        Map<String, Object> adapter = adapterSummary();
        Map<String, Object> stageSummary = stageExecutionSummary();

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("config", config());
        manifest.put("workspace_root", string(workspaceRoot));
        manifest.put("build_system", buildSystem);
        manifest.put("adapter_name", adapterName);
        manifest.put("current_analysis", currentAnalysis == null ? null : currentAnalysis.toJson());
        manifest.put("stage_history", stageHistory.stream().map(StageLog::toJson).toList());
        manifest.put("artifacts_root", layout.root().toString());
        manifest.put("final_patch_manifest", string(finalPatchManifest));
        manifest.put("legacy_regression_enabled", false);
        manifest.put("error", error.map(Exception::getMessage).orElse(null));
        manifest.put("error_type", error.map(e -> e.getClass().getSimpleName()).orElse(null));
        manifest.put("success", success);
        manifest.put("artifacts", artifacts());
        manifest.put("run_metadata", runMetadata);
        manifest.put("adapter", adapter);
        manifest.put("analysis_runs", analysisRuns);
        manifest.put("stage_timings", stageTimings);
        manifest.put("stage_execution_summary", stageSummary);
        Json.writeFile(layout.manifest(), manifest, true);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("artifacts", artifacts());
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("final_diagnostics_path", currentAnalysis == null ? null : currentAnalysis.diagnostics().toString());
        diagnostics.put("final_warning_count", currentAnalysis == null ? null : currentAnalysis.warningCount());
        report.put("diagnostics", diagnostics);
        report.put("error", error.map(Exception::getMessage).orElse(null));
        report.put("error_type", error.map(e -> e.getClass().getSimpleName()).orElse(null));
        List<Map<String, Object>> executed = new ArrayList<>();
        for (StageResult stage : stageHistory) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("artifacts", stage.artifacts());
            json.put("changed", stage.changed());
            json.put("rerun_required", stage.rerunRequired());
            json.put("stage", stage.stage());
            json.put("success", stage.success());
            executed.add(json);
        }
        report.put("executed_stages", executed);
        report.put("final_analysis", currentAnalysis == null ? null : currentAnalysis.toJson());
        report.put("success", success);
        report.put("workspace_root", string(workspaceRoot));
        report.put("repo_root", settings.repoRoot().toString());
        report.put("run_metadata", runMetadata);
        report.put("adapter", adapter);
        report.put("analysis_runs", analysisRuns);
        report.put("stage_timings", stageTimings);
        report.put("stage_execution_summary", stageSummary);
        leaks.ifPresent(value -> report.put("leaks", value));
        Json.writeFile(layout.report(), report, true);
    }

    private Map<String, Object> config() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("command", settings.command());
        config.put("repo_root", settings.repoRoot().toString());
        config.put("out_dir", settings.outDir().toString());
        config.put("keep_workspace", settings.keepWorkspace());
        config.put("workspace_mode", "copy");
        config.put("build_args", settings.buildArgs());
        config.put("compile_target", settings.compileTarget().orElse(null));
        config.put("patch_dir", null);
        config.put("checker_jar", toolchain.checkerJar().toString());
        config.put("close_injector_jar", string(toolchain.closeInjectorJar()));
        config.put("owning_field_jar", string(toolchain.owningFieldFixerJar()));
        config.put("rlfixer_jar", string(toolchain.rlfixerJar()));
        config.put("rlpatcher_jar", string(toolchain.rlpatcherJar()));
        config.put("timeouts", settings.timeouts().toJson());
        config.put("field_transformations", settings.fieldTransformations().cliName());
        return config;
    }

    private Map<String, Object> artifacts() {
        Map<String, Object> artifacts = new LinkedHashMap<>();
        artifacts.put("diagnostics_dir", layout.diagnosticsDirectory().toString());
        artifacts.put("inference_dir", layout.inferenceDirectory().toString());
        artifacts.put("logs_dir", layout.logsDirectory().toString());
        artifacts.put("manifest", layout.manifest().toString());
        artifacts.put("patch_bundle_dir", layout.patchesDirectory().toString());
        artifacts.put("patches_manifest", string(finalPatchManifest));
        artifacts.put("report", layout.report().toString());
        artifacts.put("stages_dir", layout.stagesDirectory().toString());
        return artifacts;
    }

    private Map<String, Object> runMetadata() {
        Map<String, Object> adapter = adapterSummary();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tool_version", Version.VERSION);
        metadata.put("command", settings.command());
        metadata.put("repo_root", settings.repoRoot().toString());
        metadata.put("workspace_root", string(workspaceRoot));
        metadata.put("keep_workspace", settings.keepWorkspace());
        metadata.put("build_args", settings.buildArgs());
        metadata.put("compile_target", settings.compileTarget().orElse(null));
        metadata.put("timeouts", settings.timeouts().toJson());
        metadata.put("generated_at", timestamp());
        metadata.put("output_dir", layout.root().toString());
        metadata.put("adapter_name", adapter.get("adapter_name"));
        metadata.put("selected_build_tool", adapter.get("selected_build_tool"));
        metadata.put("build_tool_source", adapter.get("build_tool_source"));
        metadata.put("java_version", javaVersion);
        metadata.put("checker_framework_version", toolchain.checkerFrameworkVersion());
        if (settings.command().equals("repair")) {
            Map<String, Object> patchTool = new LinkedHashMap<>();
            patchTool.put("binary", PatchLog.APPLIER);
            patchTool.put("flavor", PatchLog.FLAVOR);
            patchTool.put("version", PatchLog.version());
            metadata.put("patch_tool", patchTool);
        } else {
            metadata.put("patch_tool", null);
        }
        metadata.put("started_at", startedAt);
        metadata.put("completed_at", timestamp());
        metadata.put("elapsed_seconds", seconds(startedNanos));
        metadata.put("artifacts_root", layout.root().toString());
        return metadata;
    }

    private Map<String, Object> adapterSummary() {
        JsonNode metadata = null;
        if (currentAnalysis != null && Files.isRegularFile(currentAnalysis.adapterMetadata())) {
            try {
                metadata = Json.read(currentAnalysis.adapterMetadata());
            } catch (IOException e) {
                metadata = null;
            }
        }
        Map<String, Object> adapter = new LinkedHashMap<>();
        adapter.put("build_system", value(metadata, "build_system", buildSystem));
        adapter.put("adapter_name", value(metadata, "adapter_name", adapterName));
        adapter.put("selected_build_tool", value(metadata, "build_tool", null));
        adapter.put("build_tool_source", value(metadata, "build_tool_source", null));
        adapter.put("compile_target", value(metadata, "compile_target", settings.compileTarget().orElse(null)));
        adapter.put("source_root", value(metadata, "source_root", null));
        adapter.put("compiled_classes_root", value(metadata, "compiled_classes_root", null));
        adapter.put("classpath_entries_file", value(metadata, "classpath_entries_file", null));
        adapter.put("adapter_metadata_path", currentAnalysis == null ? null : currentAnalysis.adapterMetadata().toString());
        return adapter;
    }

    private static Object value(JsonNode metadata, String key, Object fallback) {
        if (metadata == null || !metadata.hasNonNull(key)) {
            return fallback;
        }
        JsonNode node = metadata.get(key);
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.forEach(item -> values.add(item.asText()));
            return values;
        }
        return node.asText();
    }

    private Map<String, Object> stageExecutionSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("executed", stageHistory.size());
        summary.put("changed", stageHistory.stream().filter(StageResult::changed).count());
        summary.put("reruns_requested", stageHistory.stream().filter(StageResult::rerunRequired).count());
        summary.put("successful", stageHistory.stream().filter(StageResult::success).count());
        summary.put("failed_attempts", stageTimings.stream().filter(timing -> Boolean.FALSE.equals(timing.get("success"))).count());
        return summary;
    }

    private static String string(Path path) {
        return path == null ? null : path.toString();
    }

    /** UTC, whole seconds, "Z" suffix: 2026-09-25T12:00:00Z. */
    public static String timestamp() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static double seconds(long startNanos) {
        return Math.round((System.nanoTime() - startNanos) / 1000.0) / 1_000_000.0;
    }
}
