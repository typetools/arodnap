package org.arodnap.engine.stages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import org.arodnap.engine.json.Json;
import org.arodnap.engine.process.CommandResult;
import org.arodnap.model.StageResult;

/** Stage logs and {@code stage_result.json}. */
public final class StageLog {
    private StageLog() {}

    /** Starts an empty log. */
    public static void reset(Path log) throws IOException {
        Files.createDirectories(log.toAbsolutePath().getParent());
        Files.writeString(log, "", StandardCharsets.UTF_8);
    }

    /** Appends a {@code == title ==} block with the command, its exit code and its output. */
    public static void append(Path log, String title, CommandResult result, String toolName) throws IOException {
        write(log, "== " + title + " ==\n" + result.log(Optional.of(toolName)) + "\n");
    }

    public static void write(Path log, String text) throws IOException {
        Files.createDirectories(log.toAbsolutePath().getParent());
        Files.writeString(log, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Writes {@code stage_result.json} into the stage's directory. */
    public static StageResult writeResult(Path stageDirectory, StageResult result) throws IOException {
        Json.writeFile(stageDirectory.resolve("stage_result.json"), toJson(result), true);
        return result;
    }

    public static java.util.Map<String, Object> toJson(StageResult result) {
        java.util.Map<String, Object> json = new java.util.LinkedHashMap<>();
        json.put("stage", result.stage());
        json.put("changed", result.changed());
        json.put("changed_files", result.changedFiles());
        json.put("rerun_required", result.rerunRequired());
        json.put("artifacts", result.artifacts());
        json.put("notes", result.notes());
        json.put("success", result.success());
        return json;
    }
}
