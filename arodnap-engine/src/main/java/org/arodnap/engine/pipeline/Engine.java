package org.arodnap.engine.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.arodnap.engine.analysis.Analysis;
import org.arodnap.engine.analysis.Analyzer;
import org.arodnap.engine.files.FilePaths;
import org.arodnap.engine.inputs.ProjectCapture;
import org.arodnap.engine.inputs.ProjectCapture.CapturedProject;
import org.arodnap.engine.inputs.UnsupportedProjectException;
import org.arodnap.engine.jdk.Jdk;
import org.arodnap.engine.jdk.JdkLocator;
import org.arodnap.engine.json.Json;
import org.arodnap.engine.process.Command;
import org.arodnap.engine.process.CommandException;
import org.arodnap.engine.process.CommandResult;
import org.arodnap.engine.process.CommandRunner;
import org.arodnap.engine.report.HtmlReport;
import org.arodnap.engine.report.LeakReport;
import org.arodnap.engine.report.RunRecord;
import org.arodnap.engine.report.Summary;
import org.arodnap.engine.stages.BundleStage;
import org.arodnap.engine.stages.CloseInjectionStage;
import org.arodnap.engine.stages.FieldTransformationsStage;
import org.arodnap.engine.stages.OwningFieldStage;
import org.arodnap.engine.stages.RlFixerStage;
import org.arodnap.engine.stages.RlPatcherStage;
import org.arodnap.engine.stages.Stage;
import org.arodnap.engine.stages.StageContext;
import org.arodnap.engine.tools.CheckerFramework;
import org.arodnap.engine.tools.Toolchain;
import org.arodnap.model.StageResult;

/**
 * Arodnap's commands, for any front end. {@code analyze} runs the Resource Leak Checker once,
 * {@code infer} runs whole-program inference first, and {@code repair} runs the whole pipeline on a
 * copy of the project and writes one verified patch:
 *
 * <pre>
 * field transformations -> analysis -> close injection -> analysis -> owning-field repair -> analysis
 *   -> RLFixer -> RLPatcher -> final analysis -> patch bundle
 * </pre>
 *
 * An analysis after a stage runs only when the stage changed the sources.
 */
public final class Engine {
    /** The repair stages after the initial analysis, in order. */
    static final List<Stage> REPAIR_STAGES = List.of(new CloseInjectionStage(), new OwningFieldStage(), new RlFixerStage(),
            new RlPatcherStage(), new BundleStage());
    private static final Set<String> UTF8_COMPATIBLE = Set.of("utf-8", "utf8", "us-ascii", "ascii");

    /** How the front end tells the user to apply a patch bundle, for the summary and the HTML report. */
    @FunctionalInterface
    public interface ApplyInstruction {
        String command(Path patchDirectory, Path repoRoot);
    }

    private final Toolchain toolchain;
    private final CommandRunner runner;
    private final JdkLocator jdks;
    private final PrintStream out;
    private final ApplyInstruction applyInstruction;

    public Engine(Toolchain toolchain, CommandRunner runner, JdkLocator jdks, PrintStream out, ApplyInstruction applyInstruction) {
        this.toolchain = toolchain;
        this.runner = runner;
        this.jdks = jdks;
        this.out = out;
        this.applyInstruction = applyInstruction;
    }

    public Toolchain toolchain() {
        return toolchain;
    }

    public CommandRunner runner() {
        return runner;
    }

    public JdkLocator jdks() {
        return jdks;
    }

    /** Runs the Resource Leak Checker once, without inference. */
    public void analyze(RunSettings settings, ProjectCapture capture) throws RunFailedException {
        analysisOnly(settings, capture, false);
    }

    /** Runs whole-program inference, then the Resource Leak Checker. */
    public void infer(RunSettings settings, ProjectCapture capture) throws RunFailedException {
        analysisOnly(settings, capture, true);
    }

    private void analysisOnly(RunSettings settings, ProjectCapture capture, boolean inference) throws RunFailedException {
        OutputLayout layout = new OutputLayout(settings.outDir());
        RunRecord record = new RunRecord(settings, toolchain, layout);
        try {
            layout.create();
        } catch (IOException e) {
            throw new RunFailedException("Cannot create the output directory " + layout.root() + ": " + e.getMessage(), e);
        }
        try (Workspace workspace = copy(settings)) {
            start(record, workspace, capture);
            try {
                record.analysis("initial", () -> {
                    RunContext context = context(settings, workspace, layout, capture, record);
                    return new Analyzer(context).analyze("initial", inference);
                });
                record.write(true, Optional.empty(), Optional.empty());
            } catch (Exception e) {
                throw fail(record, e);
            }
        }
    }

    /** Runs the whole repair pipeline and writes the verified patch bundle to {@code patches/}. */
    public void repair(RunSettings settings, ProjectCapture capture) throws RunFailedException {
        OutputLayout layout = new OutputLayout(settings.outDir());
        RunRecord record = new RunRecord(settings, toolchain, layout);
        try {
            layout.create();
        } catch (IOException e) {
            throw new RunFailedException("Cannot create the output directory " + layout.root() + ": " + e.getMessage(), e);
        }
        try (Workspace workspace = copy(settings)) {
            start(record, workspace, capture);
            try {
                // One capture of the project's build serves every analysis of the run.
                RunContext context = context(settings, workspace, layout, capture, record);
                requireUtf8ReadableSources(context);
                Analyzer analyzer = new Analyzer(context);
                // Field transformations run before the first analysis, so they cost no extra analysis.
                record.stage(FieldTransformationsStage.NAME, () -> new FieldTransformationsStage().run(context, analyzer.analysisClasses()));
                Analysis current = record.analysis("initial", () -> analyzer.analyze("initial", true));
                List<LeakReport.LabeledAnalysis> analyses = new ArrayList<>(List.of(new LeakReport.LabeledAnalysis("initial", current)));
                Optional<String> rlfixerLabel = Optional.empty();
                for (Stage stage : REPAIR_STAGES) {
                    StageContext stageContext = new StageContext(context, current, record.stageHistory());
                    StageResult result = record.stage(stage.name(), () -> stage.run(stageContext));
                    if (stage instanceof RlFixerStage) {
                        rlfixerLabel = Optional.of(current.label());
                    }
                    if (stage instanceof BundleStage) {
                        record.finalPatchManifest(promotePatchManifest(layout, Path.of(result.artifacts().get("patch_manifest"))));
                    }
                    if (stage.reanalysisLabel().isPresent() && result.rerunRequired()) {
                        String label = stage.reanalysisLabel().get();
                        current = record.analysis(label, () -> analyzer.analyze(label, true));
                        analyses.add(new LeakReport.LabeledAnalysis(label, current));
                    }
                }
                LeakResults results = leakResults(settings, layout, record, workspace, analyses, rlfixerLabel);
                record.write(true, Optional.empty(), Optional.of(results.leaks()));
                out.println(results.summary());
            } catch (Exception e) {
                throw fail(record, e);
            }
        }
    }

    private Workspace copy(RunSettings settings) throws RunFailedException {
        try {
            return Workspace.copyOf(settings.repoRoot(), settings.keepWorkspace());
        } catch (IOException e) {
            throw new RunFailedException("Cannot copy " + settings.repoRoot() + ": " + e.getMessage(), e);
        }
    }

    private static void start(RunRecord record, Workspace workspace, ProjectCapture capture) {
        record.workspace(workspace.root());
        try {
            var build = capture.detect(workspace.root());
            record.build(build.buildSystem(), build.adapterName());
        } catch (UnsupportedProjectException e) {
            // Reported when the capture itself fails.
        }
    }

    private RunContext context(RunSettings settings, Workspace workspace, OutputLayout layout, ProjectCapture capture, RunRecord record)
            throws CheckerFramework.CheckerFrameworkException, UnsupportedProjectException {
        Jdk jdk = CheckerFramework.analysisJdk(toolchain, jdks);
        record.javaVersion(javaVersion(jdk));
        CapturedProject captured = capture.capture(workspace.root(), workspace.stateDirectory());
        return new RunContext(settings, toolchain, runner, jdk, workspace, layout, captured);
    }

    private String javaVersion(Jdk jdk) {
        try {
            CommandResult result = runner.run(Command.of(List.of(jdk.java().toString(), "-version"), Path.of(".").toAbsolutePath()));
            return (result.stdout() + "\n" + result.stderr()).strip().lines().filter(line -> !line.isBlank()).findFirst().map(String::strip)
                    .orElse(null);
        } catch (CommandException e) {
            return null;
        }
    }

    private static RunFailedException fail(RunRecord record, Exception error) {
        try {
            record.write(false, Optional.of(error), Optional.empty());
        } catch (IOException e) {
            error.addSuppressed(e);
        }
        return new RunFailedException(error.getMessage(), error);
    }

    /**
     * Fails before repairing when the repair tools cannot read the sources. Analysis passes the
     * build's encoding to javac, but the Java repair tools read and write sources as UTF-8. Sources
     * in another encoding are fine as long as they are also valid UTF-8 (for example, ASCII only).
     */
    private static void requireUtf8ReadableSources(RunContext context) throws UnsupportedProjectException, IOException {
        Optional<String> encoding = context.inputs().encoding();
        if (encoding.isEmpty() || encoding.get().isEmpty() || UTF8_COMPATIBLE.contains(encoding.get().toLowerCase(Locale.ROOT))) {
            return;
        }
        List<Path> sources = new ArrayList<>(context.inputs().sources());
        sources.addAll(context.inputs().generatedSources());
        for (Path source : sources) {
            if (!Files.isRegularFile(source)) {
                continue;
            }
            try {
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(Files.readAllBytes(source)));
            } catch (CharacterCodingException e) {
                String shown = FilePaths.relativeTo(source, context.workspace().root()).orElse(source.toString());
                throw new UnsupportedProjectException("The build compiles sources as " + encoding.get() + " and " + shown
                        + " is not valid UTF-8. Arodnap's repair tools read and write sources as UTF-8, so `repair` does not support "
                        + "this project yet; `analyze` and `infer` do.");
            }
        }
    }

    /** Copies the bundle's patch next to {@code patches/manifest.json}, so {@code patches/} is self-contained. */
    static Path promotePatchManifest(OutputLayout layout, Path stageManifest) throws IOException {
        JsonNode manifest = Json.read(stageManifest);
        Files.createDirectories(layout.patchesDirectory());
        List<Map<String, Object>> patches = new ArrayList<>();
        for (JsonNode entry : iterable(manifest.get("patches"))) {
            Path source = Path.of(entry.get("patch_file").asText());
            if (!source.isAbsolute()) {
                source = stageManifest.getParent().resolve(source);
            }
            Files.copy(source, layout.patchesDirectory().resolve(source.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            ObjectNode copy = ((ObjectNode) entry).deepCopy();
            copy.put("patch_file", source.getFileName().toString());
            patches.add(toMap(copy));
        }
        Map<String, Object> promoted = new LinkedHashMap<>();
        manifest.fieldNames().forEachRemaining(name -> {
            if (!name.equals("patches")) {
                promoted.put(name, toValue(manifest.get(name)));
            }
        });
        promoted.put("patches", patches);
        return Json.writeFile(layout.patchesManifest(), promoted, true);
    }

    private record LeakResults(Map<String, Object> leaks, String summary) {}

    /**
     * Per-leak results, report.html and the terminal summary. The patch bundle is already verified at
     * this point, so a failure here is reported in report.json and on the terminal instead of failing
     * the run.
     */
    private LeakResults leakResults(RunSettings settings, OutputLayout layout, RunRecord record, Workspace workspace,
            List<LeakReport.LabeledAnalysis> analyses, Optional<String> rlfixerLabel) {
        Map<String, StageResult> stageResults = new LinkedHashMap<>();
        record.stageHistory().forEach(result -> stageResults.put(result.stage(), result));
        Path patchFile = layout.patchesDirectory().resolve(BundleStage.PATCH_FILE);
        Optional<Path> patch = Files.isRegularFile(patchFile) ? Optional.of(patchFile) : Optional.empty();
        String applyCommand = applyInstruction.command(layout.patchesDirectory(), settings.repoRoot());
        Map<String, Object> leaks;
        Path html;
        try {
            Map<String, String> stageForLabel = new LinkedHashMap<>();
            for (Stage stage : REPAIR_STAGES) {
                stage.reanalysisLabel().ifPresent(label -> stageForLabel.put(label, stage.name()));
            }
            leaks = LeakReport.build(analyses, workspace.root(), stageForLabel, rlfixerLabel, stageResults);
            leaks.put("field_changes", fieldChanges(stageResults.get(FieldTransformationsStage.NAME)));
            html = HtmlReport.write(layout.htmlReport(), leaks, settings.repoRoot(), patch, applyCommand, record.startedAt());
        } catch (Exception e) { // the report is a view of results that are already verified
            String message = "Could not build the per-leak report: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            return new LeakResults(Map.of("error", message), message);
        }
        leaks.put("html_report", html.toString());
        StageResult bundle = stageResults.get(BundleStage.NAME);
        String summary = Summary.format(leaks, settings.repoRoot(), patch, layout.patchesDirectory(),
                bundle == null ? 0 : bundle.changedFiles().size(), Optional.of(html), applyCommand);
        return new LeakResults(leaks, summary);
    }

    /** The field transformations that were kept, for the report. */
    private static Map<String, Object> fieldChanges(StageResult result) throws IOException {
        Map<String, Object> json = new LinkedHashMap<>();
        if (result == null || !result.artifacts().containsKey("field_changes")) {
            json.put("mode", null);
            json.put("changes", List.of());
            json.put("patch", null);
            return json;
        }
        JsonNode data = Json.read(Path.of(result.artifacts().get("field_changes")));
        List<Object> kept = new ArrayList<>();
        for (JsonNode change : iterable(data.get("changes"))) {
            if (change.get("kept").asBoolean()) {
                kept.add(toValue(change));
            }
        }
        json.put("mode", data.get("mode").asText());
        json.put("changes", kept);
        json.put("patch", result.artifacts().get("patch"));
        return json;
    }

    private static Iterable<JsonNode> iterable(JsonNode node) {
        return node == null ? List.of() : node::elements;
    }

    private static Map<String, Object> toMap(JsonNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        node.fieldNames().forEachRemaining(name -> map.put(name, toValue(node.get(name))));
        return map;
    }

    /** A Jackson tree as plain values, for {@link Json}. */
    static Object toValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            return toMap(node);
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(item -> list.add(toValue(item)));
            return list;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        return node.asText();
    }
}
