package org.arodnap.engine.stages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.arodnap.engine.diagnostics.CheckerWarning;
import org.arodnap.engine.files.FilePaths;
import org.arodnap.engine.json.Json;
import org.arodnap.engine.patch.PatchApplier;
import org.arodnap.engine.pipeline.PatchLog;
import org.arodnap.engine.process.Command;
import org.arodnap.engine.process.CommandException;
import org.arodnap.engine.process.CommandResult;
import org.arodnap.engine.tools.RlFixerFormats;
import org.arodnap.model.StageResult;

/**
 * RLPatcher: turns each of RLFixer's suggestions into a source edit (try-with-resources, a finally
 * block, and so on), compile-checks it, and declines edits that could change what the code does.
 * Each suggestion is independent; every one gets an outcome.
 */
public final class RlPatcherStage implements Stage {
    public static final String NAME = "rlpatcher";

    /** What happened to one of RLFixer's suggestions. The values are stable output (patch_manifest.json). */
    public enum Outcome {
        /** RLPatcher produced a patch that passed its compile check. */
        MATERIALIZED("materialized", null),
        /** RLFixer or RLPatcher found nothing to change. */
        NO_CHANGE("no_change", "needed no change"),
        /** RLPatcher's edit did not pass its compile check. */
        REJECTED("rejected", "failed RLPatcher's compile check"),
        /** RLPatcher could not place the fix without changing what the code does. */
        UNSAFE("unsafe", "could not be placed without changing what the code does"),
        /** RLPatcher does not handle this kind of suggestion. */
        UNSUPPORTED("unsupported", "are not supported by RLPatcher"),
        /** RLPatcher exited with an error. */
        CRASHED("crashed", "crashed RLPatcher"),
        /** RLPatcher ran longer than --stage-timeout and was stopped. */
        TIMED_OUT("timed_out", "exceeded --stage-timeout");

        private final String code;
        private final String description;

        Outcome(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public String code() {
            return code;
        }
    }

    private static final String PATCH_SUCCESS = "Patch applied successfully";
    private static final String PATCH_REJECTED = "Patch failed";
    private static final String PATCH_UNSAFE = "Patch not materialized (unsafe edit)";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Optional<String> reanalysisLabel() {
        return Optional.of("final");
    }

    private record Harvested(int index, RlFixerFormats.Match match, Outcome outcome, String patch) {}

    @Override
    public StageResult run(StageContext context) throws StageException {
        StageResult rlfixer = context.previous(RlFixerStage.NAME)
                .orElseThrow(() -> new StageException("RLFixer result is required before running RLPatcher."));
        Path directory = context.stageDirectory(NAME);
        Path log = directory.resolve("stage.log");
        Path patchDirectory = directory.resolve("patches");
        Path promptDirectory = directory.resolve("_tmp_prompts");
        Path manifest = directory.resolve("patch_manifest.json");
        Path rawPatch = directory.resolve("rlfixer.patch");
        Path fixes = Path.of(rlfixer.artifacts().get("fixes"));
        Path debug = Path.of(rlfixer.artifacts().get("debug"));
        try {
            requireDirectory(context.workspaceRoot(), "workspace root");
            requireFile(context.analysis().diagnostics(), "diagnostics file");
            requireDirectory(context.analysis().inferenceDirectory(), "inference directory");
            requireFile(fixes, "RLFixer fixes file");
            requireFile(debug, "RLFixer debug file");
            requireFile(context.run().toolchain().rlpatcherJar(), "RLPatcher jar");
            Files.createDirectories(patchDirectory);
            Files.createDirectories(promptDirectory);
            StageLog.reset(log);

            Path sourceRoot = FilePaths.real(context.run().inputs().sourceRoot());
            List<CheckerWarning> warnings = CheckerWarning.parseAll(RlFixerStage.read(context.analysis().diagnostics()));
            List<RlFixerFormats.FixSuggestion> suggestions = RlFixerFormats.parseFixes(RlFixerStage.read(fixes), sourceRoot);
            List<RlFixerFormats.FixSuggestion> fixable = RlFixerFormats.selectFixable(suggestions,
                    RlFixerFormats.parseDebugTable(RlFixerStage.read(debug)));
            List<RlFixerFormats.Match> matched = RlFixerFormats.matchFixesToWarnings(fixable, warnings);

            if (matched.isEmpty()) {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("stage", NAME);
                empty.put("patches", List.of());
                Json.writeFile(manifest, empty, true);
                Files.writeString(log, "No matched RLFixer materializations were available.\n", StandardCharsets.UTF_8);
                return result(directory, log, manifest, patchDirectory, List.of(), "No matched RLFixer materializations were available.");
            }

            List<Harvested> harvested = new ArrayList<>();
            int index = 0;
            for (RlFixerFormats.Match match : matched) {
                index++;
                Path prompt = promptDirectory.resolve(String.format("prompt-%04d.json", index));
                Files.writeString(prompt, RlFixerFormats.rlpatcherPrompt(match.warning(), match.fix()) + "\n", StandardCharsets.UTF_8);
                harvested.add(invoke(context, index, match, prompt, rawPatch, log));
            }

            List<Map<String, Object>> fixOutcomes = new ArrayList<>();
            for (Harvested item : harvested) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("index", item.index());
                entry.put("file", item.match().fix().relpath());
                entry.put("line", item.match().fix().line());
                entry.put("outcome", item.outcome().code());
                fixOutcomes.add(entry);
            }
            List<Map<String, Object>> patches = new ArrayList<>();
            LinkedHashSet<String> changed = new LinkedHashSet<>();
            int materialized = 0;
            int skipped = 0;
            for (Harvested item : harvested) {
                if (item.outcome() != Outcome.MATERIALIZED) {
                    continue;
                }
                materialized++;
                ToolPatches.Normalized normalized = ToolPatches.normalize(item.patch(), context.workspaceRoot());
                Map<String, String> preimages = preimageHashes(context.workspaceRoot(), normalized.changedFiles());
                String safePath = normalized.changedFiles().get(0).replace('/', '_').replace('\\', '_');
                Path patchFile = patchDirectory.resolve(String.format("patch-%04d-%s-L%d.patch", item.index(), safePath, item.match().fix().line()));
                Files.writeString(patchFile, normalized.text(), StandardCharsets.UTF_8);
                // Every patch was made against the same workspace; apply them in order and skip any
                // that no longer applies cleanly after an earlier one.
                boolean applied = applyToWorkspace(context.workspaceRoot(), patchFile, log);
                if (applied) {
                    changed.addAll(normalized.changedFiles());
                } else {
                    skipped++;
                }
                if (!preimages.keySet().equals(new LinkedHashSet<>(normalized.changedFiles()))) {
                    throw new StageException("RLPatcher stage produced inconsistent preimage hashes.");
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("patch_file", patchFile.toAbsolutePath().toString());
                entry.put("stage", NAME);
                entry.put("strip_level", 0);
                entry.put("target_root", ".");
                entry.put("changed_files", normalized.changedFiles());
                entry.put("preimage_hashes", preimages);
                entry.put("applied_to_workspace", applied);
                patches.add(entry);
            }
            Map<String, Object> manifestJson = new LinkedHashMap<>();
            manifestJson.put("stage", NAME);
            manifestJson.put("patches", patches);
            manifestJson.put("fixes", fixOutcomes);
            Json.writeFile(manifest, manifestJson, true);
            if (Files.exists(rawPatch)) {
                throw new StageException("RLPatcher stage leaked a raw patch artifact: " + rawPatch);
            }
            return result(directory, log, manifest, patchDirectory, List.copyOf(changed),
                    note(harvested, materialized - skipped, skipped));
        } catch (IOException e) {
            throw new StageException("RLPatcher stage could not write its files: " + e.getMessage(), e);
        }
    }

    private Harvested invoke(StageContext context, int index, RlFixerFormats.Match match, Path prompt, Path rawPatch, Path log)
            throws IOException, StageException {
        if (match.fix().nothingToDo()) {
            // RLPatcher would fall back to a finally block built from the warning's (possibly
            // truncated) expression text, so it is not run at all.
            return new Harvested(index, match, Outcome.NO_CHANGE, null);
        }
        Files.deleteIfExists(rawPatch);
        List<String> command = new ArrayList<>();
        command.add(context.run().jdk().java().toString());
        command.addAll(context.compileProperties());
        command.addAll(List.of("-jar", context.run().toolchain().rlpatcherJar().toString(), "--prompt", prompt.toString(),
                "--project-root", context.workspaceRoot().toString()));
        // RLPatcher edits the source in place and writes its backup back afterwards, which can
        // change the file (e.g. add a trailing newline). The stage applies patches itself, so the
        // workspace must be byte-for-byte what it was before RLPatcher ran.
        Map<Path, byte[]> originals = new LinkedHashMap<>();
        for (String file : new LinkedHashSet<>(List.of(match.warning().file(), match.fix().file()))) {
            Path path = Path.of(file);
            if (Files.isRegularFile(path)) {
                originals.put(path, Files.readAllBytes(path));
            }
        }
        CommandResult result;
        try {
            result = context.run().runStageCommand(Command.of(command, context.stageDirectory(NAME)));
        } catch (CommandException.TimedOut e) {
            StageLog.write(log, String.format("== rlpatcher_%04d ==\nTIMED_OUT: %s (--stage-timeout)\n\n", index, e.getMessage()));
            Files.deleteIfExists(rawPatch);
            return new Harvested(index, match, Outcome.TIMED_OUT, null);
        } catch (CommandException e) {
            // RLPatcher could not start at all: the environment is broken, so the stage fails.
            throw new StageException(e.getMessage(), e);
        } finally {
            for (Map.Entry<Path, byte[]> original : originals.entrySet()) {
                Path path = original.getKey();
                if (!Files.isRegularFile(path) || !Arrays.equals(Files.readAllBytes(path), original.getValue())) {
                    Files.write(path, original.getValue());
                }
            }
        }
        StageLog.append(log, String.format("rlpatcher_%04d", index), result, NAME);
        // Each suggestion is independent: an outcome other than a patch is recorded and the other
        // suggestions still run.
        String patch = Files.isRegularFile(rawPatch) ? Files.readString(rawPatch, StandardCharsets.UTF_8) : "";
        Files.deleteIfExists(rawPatch);
        Outcome outcome;
        if (!result.succeeded()) {
            outcome = Outcome.CRASHED;
        } else if (result.stdout().contains(PATCH_REJECTED)) {
            outcome = Outcome.REJECTED;
        } else if (result.stdout().contains(PATCH_UNSAFE)) {
            outcome = Outcome.UNSAFE;
        } else if (!result.stdout().contains(PATCH_SUCCESS)) {
            outcome = Outcome.UNSUPPORTED;
        } else if (!changesCode(patch)) {
            outcome = Outcome.NO_CHANGE;
        } else {
            outcome = Outcome.MATERIALIZED;
        }
        return new Harvested(index, match, outcome, outcome == Outcome.MATERIALIZED ? patch : null);
    }

    /** False for an empty diff or one that only changes whitespace (RLPatcher reprints files). */
    static boolean changesCode(String patch) {
        List<String> removed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        for (String line : patch.split("\\R", -1)) {
            if (line.startsWith("-") && !line.startsWith("---")) {
                String text = line.substring(1).strip();
                if (!text.isEmpty()) {
                    removed.add(text);
                }
            } else if (line.startsWith("+") && !line.startsWith("+++")) {
                String text = line.substring(1).strip();
                if (!text.isEmpty()) {
                    added.add(text);
                }
            }
        }
        return !removed.equals(added);
    }

    private static boolean applyToWorkspace(Path workspaceRoot, Path patch, Path log) throws IOException, StageException {
        PatchApplier.Outcome check = PatchLog.apply(workspaceRoot, patch, new PatchApplier.Options(0, true, 0, true), log,
                "dry_run:" + patch.getFileName());
        if (!check.ok()) {
            return false;
        }
        PatchApplier.Outcome applied = PatchLog.apply(workspaceRoot, patch, new PatchApplier.Options(0, false, 0, true), log,
                "apply:" + patch.getFileName());
        if (!applied.ok()) {
            throw new StageException("Patch " + patch.getFileName() + " passed its dry run but failed to apply. See log: " + log);
        }
        return true;
    }

    private static Map<String, String> preimageHashes(Path workspaceRoot, List<String> files) throws IOException, StageException {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String file : files) {
            Path path = workspaceRoot.resolve(file);
            if (!Files.isRegularFile(path)) {
                throw new StageException("Cannot compute preimage hash for missing workspace file: " + path);
            }
            hashes.put(file, sha256(Files.readAllBytes(path)));
        }
        return hashes;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String note(List<Harvested> harvested, int applied, int skipped) {
        Map<Outcome, Integer> counts = new LinkedHashMap<>();
        for (Outcome outcome : Outcome.values()) {
            counts.put(outcome, 0);
        }
        harvested.forEach(item -> counts.merge(item.outcome(), 1, Integer::sum));
        StringBuilder note = new StringBuilder("RLPatcher materialized " + counts.get(Outcome.MATERIALIZED) + " of " + harvested.size()
                + " RLFixer suggestion(s); applied " + applied + ", skipped " + skipped + " that conflicted with earlier patches.");
        List<String> others = new ArrayList<>();
        for (Outcome outcome : Outcome.values()) {
            if (outcome != Outcome.MATERIALIZED && counts.get(outcome) > 0) {
                others.add(counts.get(outcome) + " " + outcome.description);
            }
        }
        if (!others.isEmpty()) {
            note.append(" Not materialized: ").append(String.join(", ", others)).append('.');
        }
        return note.toString();
    }

    private static StageResult result(Path directory, Path log, Path manifest, Path patchDirectory, List<String> changed, String note)
            throws IOException {
        Map<String, String> artifacts = new LinkedHashMap<>();
        artifacts.put("log", log.toString());
        artifacts.put("patch_manifest", manifest.toString());
        artifacts.put("patch_dir", patchDirectory.toString());
        return StageLog.writeResult(directory, new StageResult(NAME, !changed.isEmpty(), changed, !changed.isEmpty(), artifacts,
                List.of(note), true));
    }

    private static void requireFile(Path path, String label) throws StageException {
        if (!Files.isRegularFile(path)) {
            throw new StageException("Missing " + label + ": " + path);
        }
    }

    private static void requireDirectory(Path path, String label) throws StageException {
        if (!Files.isDirectory(path)) {
            throw new StageException("Missing " + label + ": " + path);
        }
    }
}
