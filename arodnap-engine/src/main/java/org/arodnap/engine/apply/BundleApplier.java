package org.arodnap.engine.apply;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.arodnap.engine.json.Json;
import org.arodnap.engine.patch.PatchApplier;
import org.arodnap.engine.pipeline.PatchLog;
import org.arodnap.engine.pipeline.Workspace;
import org.arodnap.engine.stages.RlPatcherStage;

/**
 * Applies a patch bundle to the project: the only command that changes it. Every file must still
 * be exactly what the patch was made from (by hash); the bundle is first applied to a copy, and
 * only when that succeeds to the project.
 */
public final class BundleApplier {
    private BundleApplier() {}

    /** A patch bundle cannot be validated or applied safely. */
    public static final class ApplyException extends Exception {
        private static final long serialVersionUID = 1L;

        public ApplyException(String message) {
            super(message);
        }
    }

    private record Entry(Path patchFile, String stage, int stripLevel, String targetRoot, List<String> changedFiles,
            Map<String, String> preimageHashes) {}

    /**
     * @param repoRoot the project to change
     * @param patchDirectory a {@code patches/} directory with its {@code manifest.json}
     * @param log where to log the patch applications
     */
    public static void apply(Path repoRoot, Path patchDirectory, Path log, boolean keepWorkspace) throws ApplyException, IOException {
        if (!Files.isDirectory(patchDirectory)) {
            throw new ApplyException("Patch directory does not exist: " + patchDirectory);
        }
        if (!Files.isDirectory(repoRoot)) {
            throw new ApplyException("Target repo does not exist: " + repoRoot);
        }
        Path manifest = findManifest(patchDirectory);
        List<Entry> entries = load(manifest, patchDirectory);
        Files.createDirectories(log.toAbsolutePath().getParent());
        Files.writeString(log, "", StandardCharsets.UTF_8);
        validatePreimages(repoRoot, entries);
        try (Workspace workspace = Workspace.copyOf(repoRoot, keepWorkspace)) {
            applyEntries(workspace.root(), entries, true, log);
        }
        validatePreimages(repoRoot, entries);
        applyEntries(repoRoot, entries, false, log);
    }

    private static Path findManifest(Path patchDirectory) throws ApplyException {
        List<Path> candidates = new ArrayList<>(List.of(patchDirectory.resolve("manifest.json"), patchDirectory.resolve("patch_manifest.json")));
        if (patchDirectory.getFileName() != null && patchDirectory.getFileName().toString().equals("patches")) {
            candidates.add(patchDirectory.getParent().resolve("manifest.json"));
            candidates.add(patchDirectory.getParent().resolve("patch_manifest.json"));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath();
            }
        }
        throw new ApplyException("Patch manifest not found under patch directory: " + patchDirectory);
    }

    private static List<Entry> load(Path manifest, Path patchDirectory) throws ApplyException {
        JsonNode payload;
        try {
            payload = Json.read(manifest);
        } catch (IOException e) {
            throw new ApplyException("Malformed patch manifest JSON: " + manifest);
        }
        if (!payload.isObject()) {
            throw new ApplyException("Malformed patch manifest payload: " + manifest);
        }
        JsonNode patches = payload.get("patches");
        if (patches == null || !patches.isArray()) {
            throw new ApplyException("Patch manifest must contain a 'patches' list: " + manifest);
        }
        List<Entry> entries = new ArrayList<>();
        for (JsonNode entry : patches) {
            entries.add(entry(entry, patchDirectory, manifest));
        }
        return entries;
    }

    private static Entry entry(JsonNode entry, Path patchDirectory, Path manifest) throws ApplyException {
        if (!entry.isObject()) {
            throw new ApplyException("Patch manifest entry must be an object: " + manifest);
        }
        JsonNode patchFile = entry.get("patch_file");
        if (patchFile == null || !patchFile.isTextual() || patchFile.asText().isEmpty()) {
            throw new ApplyException("Patch manifest entry is missing a valid patch_file: " + manifest);
        }
        Path candidate = Path.of(patchFile.asText());
        Path resolved = (candidate.isAbsolute() ? candidate : patchDirectory.resolve(candidate)).toAbsolutePath().normalize();
        if (!Files.isRegularFile(resolved)) {
            throw new ApplyException("Patch file does not exist: " + resolved);
        }
        JsonNode stage = entry.get("stage");
        JsonNode strip = entry.get("strip_level");
        JsonNode target = entry.get("target_root");
        JsonNode changed = entry.get("changed_files");
        JsonNode preimages = entry.get("preimage_hashes");
        if (stage == null || !stage.isTextual() || stage.asText().isEmpty()) {
            throw new ApplyException("Patch manifest entry is missing a valid stage: " + manifest);
        }
        if (strip == null || !strip.isInt() || strip.asInt() < 0) {
            throw new ApplyException("Patch manifest entry is missing a valid strip_level: " + manifest);
        }
        if (target == null || !target.isTextual() || target.asText().isEmpty()) {
            throw new ApplyException("Patch manifest entry is missing a valid target_root: " + manifest);
        }
        if (changed == null || !changed.isArray()) {
            throw new ApplyException("Patch manifest entry is missing valid changed_files: " + manifest);
        }
        List<String> changedFiles = new ArrayList<>();
        for (JsonNode file : changed) {
            if (!file.isTextual() || file.asText().isEmpty()) {
                throw new ApplyException("Patch manifest entry is missing valid changed_files: " + manifest);
            }
            changedFiles.add(relative(file.asText(), "changed_files entry"));
        }
        if (preimages == null || !preimages.isObject()) {
            throw new ApplyException("Patch manifest entry is missing valid preimage_hashes: " + manifest);
        }
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = preimages.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> hash = it.next();
            if (!hash.getValue().isTextual()) {
                throw new ApplyException("Patch manifest entry is missing valid preimage_hashes: " + manifest);
            }
            hashes.put(hash.getKey(), hash.getValue().asText());
        }
        if (!hashes.keySet().equals(new java.util.HashSet<>(changedFiles))) {
            throw new ApplyException("Patch manifest entry preimage hashes do not match changed_files: " + manifest);
        }
        return new Entry(resolved, stage.asText(), strip.asInt(), relative(target.asText(), "target_root"), changedFiles, hashes);
    }

    private static String relative(String pathText, String label) throws ApplyException {
        if (pathText.equals(".")) {
            return pathText;
        }
        Path path = Path.of(pathText);
        if (path.isAbsolute()) {
            throw new ApplyException("Unsupported " + label + " outside repo root: " + pathText);
        }
        for (Path name : path) {
            if (name.toString().equals("..")) {
                throw new ApplyException("Unsupported " + label + " outside repo root: " + pathText);
            }
        }
        String normalized = path.toString().replace('\\', '/');
        if (normalized.isEmpty()) {
            throw new ApplyException("Unsupported empty " + label + ".");
        }
        return normalized;
    }

    private static void validatePreimages(Path repoRoot, List<Entry> entries) throws ApplyException, IOException {
        for (Entry entry : entries) {
            Path target = target(repoRoot, entry);
            if (!Files.isDirectory(target)) {
                throw new ApplyException("Patch target root does not exist: " + target);
            }
            for (String file : entry.changedFiles()) {
                Path path = repoRoot.resolve(file);
                if (!Files.isRegularFile(path)) {
                    throw new ApplyException("Patch target file does not exist: " + path);
                }
                String observed = RlPatcherStage.sha256(Files.readAllBytes(path));
                String expected = entry.preimageHashes().get(file);
                if (!observed.equals(expected)) {
                    throw new ApplyException("Preimage hash mismatch for " + file + ": expected " + expected + ", got " + observed);
                }
            }
        }
    }

    private static void applyEntries(Path repoRoot, List<Entry> entries, boolean checkOnly, Path log) throws ApplyException, IOException {
        for (Entry entry : entries) {
            PatchApplier.Outcome outcome = PatchLog.apply(target(repoRoot, entry), entry.patchFile(),
                    new PatchApplier.Options(entry.stripLevel(), checkOnly, 0, false), log,
                    (checkOnly ? "apply_dry_run" : "apply_patch") + ":" + entry.stage());
            if (!outcome.ok()) {
                String output = !outcome.errors().isEmpty() ? String.join("\n", outcome.errors())
                        : !outcome.messages().isEmpty() ? String.join("\n", outcome.messages()) : "<no patch output>";
                throw new ApplyException("Patch " + (checkOnly ? "dry-run validation" : "apply") + " failed for " + entry.patchFile()
                        + " (stage=" + entry.stage() + ", strip_level=" + entry.stripLevel() + ", target_root=" + entry.targetRoot()
                        + ", patch_tool=" + PatchLog.version() + "): " + output);
            }
        }
    }

    private static Path target(Path repoRoot, Entry entry) {
        return entry.targetRoot().equals(".") ? repoRoot : repoRoot.resolve(entry.targetRoot()).toAbsolutePath().normalize();
    }
}
