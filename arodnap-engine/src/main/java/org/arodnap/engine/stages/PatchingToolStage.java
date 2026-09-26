package org.arodnap.engine.stages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.arodnap.engine.patch.PatchApplier;
import org.arodnap.engine.pipeline.PatchLog;
import org.arodnap.engine.process.Command;
import org.arodnap.engine.process.CommandException;
import org.arodnap.engine.process.CommandResult;
import org.arodnap.model.StageResult;

/**
 * A stage whose tool reads the leak warnings and writes one unified diff where
 * {@code -Darodnap.patchFile} points. The diff is normalized to repository-relative paths and
 * applied to the workspace by the engine, never by the tool.
 */
abstract class PatchingToolStage implements Stage {
    /** The tool's command line, before {@code -jar}: the java launcher and the system properties. */
    abstract List<String> command(StageContext context, List<String> javaProperties);

    abstract String patchFileName();

    abstract String rawPatchFileName();

    abstract String logTitle();

    abstract String diagnosticsLabel();

    abstract String toolName();

    /** Context lines at each end of a hunk that may differ when the patch is applied, like {@code patch -F}. */
    int fuzz() {
        return 0;
    }

    @Override
    public StageResult run(StageContext context) throws StageException {
        Path diagnostics = context.analysis().diagnostics();
        Path directory = context.stageDirectory(name());
        Path log = directory.resolve("stage.log");
        // The tool writes its raw diff outside the workspace so it can never leak into sources.
        Path rawPatch = directory.resolve(rawPatchFileName());
        Path patch = directory.resolve(patchFileName());
        try {
            if (!Files.isRegularFile(diagnostics)) {
                throw new StageException("Missing diagnostics file for " + diagnosticsLabel() + ": " + diagnostics);
            }
            Files.createDirectories(directory);
            Files.deleteIfExists(rawPatch);

            List<String> properties = new ArrayList<>();
            properties.add("-Darodnap.patchFile=" + rawPatch);
            properties.addAll(context.compileProperties());
            CommandResult result;
            try {
                result = context.run().runStageCommand(Command.of(command(context, properties), context.workspaceRoot()));
            } catch (CommandException.TimedOut e) {
                throw new StageException(e.getMessage() + " (--stage-timeout)", e);
            } catch (CommandException e) {
                throw new StageException(e.getMessage(), e);
            }
            StageLog.append(log, logTitle(), result, name());
            if (!result.succeeded()) {
                throw new StageException(toolName() + " failed for " + context.workspaceRoot() + ". See log: " + log);
            }

            if (!Files.isRegularFile(rawPatch) || Files.size(rawPatch) == 0) {
                Map<String, String> artifacts = new LinkedHashMap<>();
                artifacts.put("log", log.toString());
                return StageLog.writeResult(directory, new StageResult(name(), false, List.of(), false, artifacts,
                        List.of("No " + diagnosticsLabel().replace(' ', '-') + " patch was generated."), true));
            }
            ToolPatches.Normalized normalized = ToolPatches.normalize(Files.readString(rawPatch, StandardCharsets.UTF_8), context.workspaceRoot());
            Files.writeString(patch, normalized.text(), StandardCharsets.UTF_8);
            Files.deleteIfExists(rawPatch);

            PatchApplier.Outcome applied = PatchLog.apply(context.workspaceRoot(), patch,
                    new PatchApplier.Options(0, false, fuzz(), true), log, "apply_normalized_patch");
            if (!applied.ok()) {
                throw new StageException("Failed to apply normalized " + diagnosticsLabel().replace(' ', '-') + " patch. See log: " + log);
            }
            Map<String, String> artifacts = new LinkedHashMap<>();
            artifacts.put("log", log.toString());
            artifacts.put("patch", patch.toString());
            return StageLog.writeResult(directory, new StageResult(name(), true, normalized.changedFiles(), true, artifacts,
                    List.of("Applied " + diagnosticsLabel().replace(' ', '-') + " patch affecting " + normalized.changedFiles().size()
                            + " file(s)."),
                    true));
        } catch (IOException e) {
            throw new StageException(name() + " could not write its files: " + e.getMessage(), e);
        }
    }
}
