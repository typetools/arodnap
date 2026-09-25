package org.arodnap.engine.report;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.arodnap.engine.analysis.Analysis;
import org.arodnap.engine.diagnostics.Diagnostic;
import org.arodnap.engine.files.FilePaths;
import org.arodnap.engine.json.Json;
import org.arodnap.engine.stages.RlFixerStage;
import org.arodnap.engine.stages.RlPatcherStage;
import org.arodnap.engine.tools.RlFixerFormats;
import org.arodnap.model.StageResult;

/**
 * Per-warning results of a repair run: which resource leaks were fixed, and why the others were not.
 *
 * <p>Every analysis run (initial, after each source-changing stage, final) is parsed, and each
 * warning is followed from run to run. A warning is identified by its file, its Checker Framework
 * key and its structured {@code -Adetailedmsgtext} fields; line numbers only order warnings that are
 * otherwise identical, because the patches shift lines. The stage after which a warning disappears
 * is the one that fixed it. For a leak that remains, the reason comes from RLFixer's debug table and
 * RLPatcher's per-suggestion outcomes.
 */
public final class LeakReport {
    /** Why a leak remains, keyed by reason code. Codes are stable output; texts may be reworded. */
    public static final Map<String, String> REASONS;

    static {
        Map<String, String> reasons = new LinkedHashMap<>();
        reasons.put("rlfixer_unmatched", "RLFixer could not find the leaking allocation in the compiled code");
        reasons.put("rlfixer_duplicate", "RLFixer treats it as a duplicate of another warning");
        reasons.put("rlfixer_unfixable", "RLFixer found no fix (for example, the resource is stored in a field or collection)");
        reasons.put("no_suggestion", "RLFixer suggested no fix");
        reasons.put("no_change", "nothing to change (for example, a returned resource whose callers are not in the project)");
        reasons.put("unsafe", "a fix would have to reorder code, which could change behavior");
        reasons.put("rejected", "the fix did not compile");
        reasons.put("unsupported", "RLPatcher does not support this kind of fix");
        reasons.put("crashed", "RLPatcher crashed on this fix");
        reasons.put("timed_out", "RLPatcher exceeded --stage-timeout");
        reasons.put("conflict", "its fix overlapped a fix applied earlier and was skipped");
        reasons.put("fix_applied_warning_remains", "a fix was applied, but the checker still reports the leak");
        reasons.put("appeared_after_fixes", "it appeared after RLPatcher's fixes (a fix exposed or moved it)");
        reasons.put("not_analyzed_by_rlfixer", "it was not part of RLFixer's input");
        REASONS = java.util.Collections.unmodifiableMap(reasons);
    }

    private static final Pattern PATCH_INDEX = Pattern.compile("patch-(\\d+)-");

    private LeakReport() {}

    /** An analysis and its label, in run order. */
    public record LabeledAnalysis(String label, Analysis analysis) {}

    private static final class Tracked {
        final String firstSeen;
        final Diagnostic diagnostic;
        final LinkedHashMap<String, Diagnostic> runs = new LinkedHashMap<>();
        String fixedBy;
        String reason;
        String fixPatch;

        Tracked(String firstSeen, Diagnostic diagnostic) {
            this.firstSeen = firstSeen;
            this.diagnostic = diagnostic;
        }
    }

    /** Maps each warning of {@code current} (by index) to the same warning in {@code previous}. */
    static Map<Integer, Integer> matchRuns(List<Diagnostic> previous, List<Diagnostic> current) {
        Map<List<String>, List<Integer>> groups = new HashMap<>();
        IntStream.range(0, previous.size()).boxed().sorted(Comparator.comparingInt(i -> previous.get(i).line()))
                .forEach(i -> groups.computeIfAbsent(previous.get(i).identity(), key -> new ArrayList<>()).add(i));
        Map<Integer, Integer> matched = new HashMap<>();
        IntStream.range(0, current.size()).boxed().sorted(Comparator.comparingInt(i -> current.get(i).line())).forEach(i -> {
            List<Integer> candidates = groups.get(current.get(i).identity());
            if (candidates != null && !candidates.isEmpty()) {
                matched.put(i, candidates.remove(0));
            }
        });
        return matched;
    }

    /**
     * The {@code leaks} section of report.json.
     *
     * @param stageForLabel which stage's changes an analysis label follows, e.g. {@code post_close_injector -> close_injector}
     * @param rlfixerLabel the label of the analysis RLFixer ran on, if it ran
     * @param stageResults every stage's result, by name
     */
    public static Map<String, Object> build(List<LabeledAnalysis> analyses, Path workspaceRoot, Map<String, String> stageForLabel,
            Optional<String> rlfixerLabel, Map<String, StageResult> stageResults) throws IOException {
        List<List<Diagnostic>> runs = new ArrayList<>();
        for (LabeledAnalysis analysis : analyses) {
            runs.add(Diagnostic.parseAll(readReplacing(analysis.analysis().diagnostics()), workspaceRoot));
        }
        List<Tracked> tracked = new ArrayList<>();
        Map<Integer, Tracked> live = new HashMap<>();
        for (int position = 0; position < runs.size(); position++) {
            String label = analyses.get(position).label();
            List<Diagnostic> diagnostics = runs.get(position);
            Map<Integer, Integer> matches = position > 0 ? matchRuns(runs.get(position - 1), diagnostics) : Map.of();
            Map<Integer, Tracked> nextLive = new HashMap<>();
            for (int index = 0; index < diagnostics.size(); index++) {
                Tracked warning;
                if (matches.containsKey(index)) {
                    warning = live.remove(matches.get(index));
                } else {
                    warning = new Tracked(label, diagnostics.get(index));
                    tracked.add(warning);
                }
                warning.runs.put(label, diagnostics.get(index));
                nextLive.put(index, warning);
            }
            for (Tracked gone : live.values()) {
                gone.fixedBy = stageForLabel.getOrDefault(label, label);
            }
            live = nextLive;
        }

        String finalLabel = analyses.get(analyses.size() - 1).label();
        RlFixerView view = RlFixerView.load(analyses, rlfixerLabel, stageResults);
        for (Tracked warning : tracked) {
            if (!warning.diagnostic.isLeak()) {
                continue;
            }
            if (warning.fixedBy == null) {
                warning.reason = view.reason(warning, finalLabel);
            } else if (warning.fixedBy.equals(RlPatcherStage.NAME)) {
                warning.fixPatch = view.patchFor(warning);
            } else if (stageResults.containsKey(warning.fixedBy)) {
                warning.fixPatch = stageResults.get(warning.fixedBy).artifacts().get("patch");
            }
        }

        List<Tracked> leaks = tracked.stream().filter(warning -> warning.diagnostic.isLeak()).toList();
        String initialLabel = analyses.get(0).label();
        List<Tracked> others = tracked.stream().filter(warning -> !warning.diagnostic.isLeak() && warning.runs.containsKey(finalLabel)).toList();
        Map<String, Integer> reasons = new HashMap<>();
        Map<String, Integer> fixedBy = new TreeMap<>();
        for (Tracked warning : leaks) {
            if (warning.reason != null) {
                reasons.merge(warning.reason, 1, Integer::sum);
            }
            if (warning.fixedBy != null) {
                fixedBy.merge(warning.fixedBy, 1, Integer::sum);
            }
        }
        Map<String, Integer> sortedReasons = new LinkedHashMap<>();
        reasons.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(entry -> -entry.getValue()).thenComparing(Map.Entry::getKey))
                .forEach(entry -> sortedReasons.put(entry.getKey(), entry.getValue()));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("initial", leaks.stream().filter(warning -> warning.firstSeen.equals(initialLabel)).count());
        summary.put("found_during_repair", leaks.stream().filter(warning -> !warning.firstSeen.equals(initialLabel)).count());
        summary.put("fixed", leaks.stream().filter(warning -> warning.fixedBy != null).count());
        summary.put("remaining", leaks.stream().filter(warning -> warning.fixedBy == null).count());
        summary.put("fixed_by_stage", fixedBy);
        summary.put("remaining_by_reason", sortedReasons);
        summary.put("other_checker_warnings", others.stream().filter(warning -> warning.diagnostic.fromChecker()).count());
        summary.put("javac_warnings", others.stream().filter(warning -> !warning.diagnostic.fromChecker()).count());

        List<Map<String, Object>> warnings = new ArrayList<>();
        for (int i = 0; i < leaks.size(); i++) {
            warnings.add(leakJson(i + 1, leaks.get(i)));
        }
        List<Map<String, Object>> otherWarnings = new ArrayList<>();
        for (Tracked warning : others) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("file", warning.diagnostic.path());
            json.put("line", warning.runs.get(finalLabel).line());
            json.put("kind", warning.diagnostic.key());
            json.put("checker", warning.diagnostic.fromChecker());
            otherWarnings.add(json);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("summary", summary);
        report.put("reasons", REASONS);
        report.put("warnings", warnings);
        report.put("other_warnings", otherWarnings);
        return report;
    }

    private static Map<String, Object> leakJson(int number, Tracked warning) {
        Diagnostic first = warning.diagnostic;
        List<String> fields = new ArrayList<>(first.fields());
        while (fields.size() < 4) {
            fields.add("");
        }
        String finalizer = fields.get(0).startsWith("method ") ? fields.get(0).substring("method ".length()) : fields.get(0);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", number);
        json.put("file", first.path());
        json.put("line", first.line());
        json.put("first_seen", warning.firstSeen);
        json.put("finalizer", finalizer.strip());
        json.put("resource", fields.get(1));
        json.put("type", fields.get(2));
        json.put("leak", fields.get(3));
        json.put("status", warning.fixedBy != null ? "fixed" : "remaining");
        json.put("fixed_by", warning.fixedBy);
        json.put("reason", warning.reason);
        json.put("fix_patch", warning.fixPatch);
        Diagnostic last = new ArrayList<>(warning.runs.values()).get(warning.runs.size() - 1);
        if (warning.fixedBy == null && last.line() != first.line()) {
            json.put("line_after_repair", last.line());
        }
        return json;
    }

    /** What RLFixer and RLPatcher did with each warning of the analysis RLFixer ran on. */
    private record RlFixerView(Optional<String> label, Path sourceRoot, Path workspaceRoot, Map<RlFixerFormats.Key, RlFixerFormats.DebugStatus> debug,
            Map<RlFixerFormats.Key, PatcherOutcome> outcomes) {

        record PatcherOutcome(String outcome, boolean applied, String patchFile) {}

        static RlFixerView load(List<LabeledAnalysis> analyses, Optional<String> label, Map<String, StageResult> stageResults) throws IOException {
            Optional<Analysis> analysis = label.flatMap(name -> analyses.stream().filter(item -> item.label().equals(name)).reduce((a, b) -> b))
                    .map(LabeledAnalysis::analysis);
            StageResult rlfixer = stageResults.get(RlFixerStage.NAME);
            if (analysis.isEmpty() || rlfixer == null) {
                return new RlFixerView(Optional.empty(), null, null, Map.of(), Map.of());
            }
            JsonNode metadata = Json.read(analysis.get().adapterMetadata());
            Path sourceRoot = FilePaths.real(Path.of(metadata.get("source_root").asText()));
            Map<RlFixerFormats.Key, RlFixerFormats.DebugStatus> debug = rlfixer.artifacts().containsKey("debug")
                    ? RlFixerFormats.parseDebugTable(readReplacing(Path.of(rlfixer.artifacts().get("debug")))) : Map.of();
            return new RlFixerView(label, sourceRoot, FilePaths.real(analysis.get().workspaceRoot()), debug,
                    patcherOutcomes(stageResults.get(RlPatcherStage.NAME)));
        }

        Optional<RlFixerFormats.Key> key(Tracked warning) {
            if (label.isEmpty()) {
                return Optional.empty();
            }
            Diagnostic diagnostic = warning.runs.get(label.get());
            if (diagnostic == null) {
                return Optional.empty();
            }
            return FilePaths.relativeTo(workspaceRoot.resolve(diagnostic.path()), sourceRoot)
                    .map(relative -> new RlFixerFormats.Key(relative, diagnostic.line()));
        }

        String reason(Tracked warning, String finalLabel) {
            Optional<RlFixerFormats.Key> key = key(warning);
            if (key.isEmpty()) {
                return warning.firstSeen.equals(finalLabel) ? "appeared_after_fixes" : "not_analyzed_by_rlfixer";
            }
            PatcherOutcome outcome = outcomes.get(key.get());
            if (outcome != null) {
                if (outcome.outcome().equals("materialized")) {
                    return outcome.applied() ? "fix_applied_warning_remains" : "conflict";
                }
                return REASONS.containsKey(outcome.outcome()) ? outcome.outcome() : "unsupported";
            }
            RlFixerFormats.DebugStatus status = debug.get(key.get());
            if (status != null && status != RlFixerFormats.DebugStatus.FIXABLE) {
                return "rlfixer_" + status.name().toLowerCase(java.util.Locale.ROOT);
            }
            return "no_suggestion";
        }

        String patchFor(Tracked warning) {
            Optional<RlFixerFormats.Key> key = key(warning);
            if (key.isEmpty() || !outcomes.containsKey(key.get())) {
                return null;
            }
            PatcherOutcome outcome = outcomes.get(key.get());
            return outcome.outcome().equals("materialized") && outcome.applied() ? outcome.patchFile() : null;
        }

        static Map<RlFixerFormats.Key, PatcherOutcome> patcherOutcomes(StageResult result) throws IOException {
            if (result == null || !result.artifacts().containsKey("patch_manifest")) {
                return Map.of();
            }
            JsonNode manifest = Json.read(Path.of(result.artifacts().get("patch_manifest")));
            Map<Integer, PatcherOutcome> patches = new HashMap<>();
            for (JsonNode entry : iterable(manifest.get("patches"))) {
                String file = entry.get("patch_file").asText();
                Matcher matcher = PATCH_INDEX.matcher(Path.of(file).getFileName().toString());
                if (matcher.lookingAt()) {
                    JsonNode applied = entry.get("applied_to_workspace");
                    patches.put(Integer.parseInt(matcher.group(1)), new PatcherOutcome("", applied == null || applied.asBoolean(), file));
                }
            }
            Map<RlFixerFormats.Key, PatcherOutcome> outcomes = new HashMap<>();
            for (JsonNode fix : iterable(manifest.get("fixes"))) {
                PatcherOutcome patch = patches.getOrDefault(fix.get("index").asInt(), new PatcherOutcome("", false, null));
                outcomes.put(new RlFixerFormats.Key(fix.get("file").asText(), fix.get("line").asInt()),
                        new PatcherOutcome(fix.get("outcome").asText(), patch.applied(), patch.patchFile()));
            }
            return outcomes;
        }
    }

    private static Iterable<JsonNode> iterable(JsonNode node) {
        return node == null ? List.of() : node::elements;
    }

    static String readReplacing(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
