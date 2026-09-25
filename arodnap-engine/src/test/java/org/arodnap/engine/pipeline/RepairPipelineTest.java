package org.arodnap.engine.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.arodnap.engine.apply.BundleApplier;
import org.arodnap.engine.files.FilePaths;
import org.arodnap.engine.inputs.ProjectCapture;
import org.arodnap.engine.json.Json;
import org.arodnap.engine.pipeline.RunSettings.FieldTransformationMode;
import org.arodnap.engine.report.Summary;
import org.arodnap.model.BuildDescription;
import org.arodnap.model.CompileUnit;
import org.arodnap.model.ProjectInputs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The repair pipeline's wiring, with every tool faked (see {@link FakeTools}). */
class RepairPipelineTest {
    private static final String SOURCE = """
            package demo;

            import java.io.FileInputStream;
            import java.io.IOException;

            public class Demo {
                public int read(String path) throws IOException {
                    FileInputStream in = new FileInputStream(path);
                    return in.read();
                }
            }
            """;
    private static final String FIXED = SOURCE.replace("""
                    FileInputStream in = new FileInputStream(path);
                    return in.read();
            """, """
                    try (FileInputStream in = new FileInputStream(path)) {
                        return in.read();
                    }
            """);
    /** The Resource Leak Checker's warning for the leak on line 8, with -Adetailedmsgtext. */
    private static final String LEAK = "{ROOT}/src/demo/Demo.java:8: warning: [required.method.not.called] $$ 4 $$ method close $$ in $$ "
            + "java.io.FileInputStream $$ possible exceptional exit due to in.read() with exception type java.io.IOException $$ ( 173, 205 ) "
            + "$$ @MustCall method close may not have been invoked on in or any of its aliases.\n"
            + "        FileInputStream in = new FileInputStream(path);\n                        ^\n1 warning\n";

    @TempDir
    Path temp;
    Path project;
    FakeTools tools;
    ByteArrayOutputStream printed = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws IOException {
        project = Files.createDirectories(temp.resolve("demo-project"));
        Files.createDirectories(project.resolve("src/demo"));
        Files.writeString(project.resolve("src/demo/Demo.java"), SOURCE);
        tools = new FakeTools(Files.createDirectories(temp.resolve("fakes")));
    }

    private Engine engine() {
        return new Engine(tools.toolchain, tools, tools.jdks(), new PrintStream(printed, true, StandardCharsets.UTF_8), Summary::cliApplyCommand);
    }

    private RunSettings settings() {
        return new RunSettings("repair", project, temp.resolve("out"), false, Timeouts.NONE, FieldTransformationMode.RESOURCES, List.of(),
                Optional.empty());
    }

    /** The project's inputs, as a front end would capture them in the copy. */
    private static ProjectCapture capture() {
        return new ProjectCapture() {
            @Override
            public BuildDescription detect(Path root) {
                return new BuildDescription("command", "command", root, List.of(), "command", "", List.of());
            }

            @Override
            public CapturedProject capture(Path root, Path state) {
                Path source = FilePaths.real(root.resolve("src/demo/Demo.java"));
                CompileUnit unit = new CompileUnit(root, List.of(source), List.of(), Optional.empty(), Optional.empty(), Optional.of(17),
                        Optional.of("UTF-8"), List.of(), List.of(), Optional.empty());
                ProjectInputs inputs = new ProjectInputs(List.of(unit), List.of(source), List.of(), List.of(), Optional.of(17),
                        Optional.of("UTF-8"), source.getParent().getParent());
                return new CapturedProject(inputs, detect(root), List.of());
            }
        };
    }

    /** RLFixer's report and debug table for the leak, and RLPatcher's patch for it. */
    private void rlfixerAndRlPatcherFixTheLeak() {
        tools.rlfixer = command -> {
            Path root = Path.of(FakeTools.value(command, "-projectDir"));
            Files.writeString(Path.of(FakeTools.value(command, "-debugOutput")), "Index^Source File^Line Number^Matched Method^MatchedInstruction^"
                    + "Aliases^Classification^Duplicate^Unfixable^Comments\n0^demo/Demo.java^8^demo.Demo.read(Ljava/lang/String;)I^x^NULL^INVOKE,^"
                    + "false^false^Try-catch Fix;\n");
            return FakeTools.ok(command, "SOURCE LEVEL FIXES\n1] " + root.resolve("demo/Demo.java") + "; Line number 8\n"
                    + "+++ Add following code at line 8\ntry-with-resources\n------------------------------\n");
        };
        tools.rlpatcher = command -> {
            Path file = FilePaths.real(Path.of(FakeTools.value(command, "--project-root")).resolve("src/demo/Demo.java"));
            String patch = "--- " + file + "\n+++ " + file + "\n@@ -7,5 +7,6 @@\n     public int read(String path) throws IOException {\n"
                    + "-        FileInputStream in = new FileInputStream(path);\n-        return in.read();\n"
                    + "+        try (FileInputStream in = new FileInputStream(path)) {\n+            return in.read();\n+        }\n"
                    + "     }\n }\n";
            Files.writeString(command.workingDirectory().resolve("rlfixer.patch"), patch);
            return FakeTools.ok(command, "Patch applied successfully\n");
        };
    }

    @Test
    void aLeakFixedByRlPatcherEndsInAVerifiedPatch() throws Exception {
        tools.diagnostics.add(LEAK);
        tools.diagnostics.add("");
        rlfixerAndRlPatcherFixTheLeak();

        engine().repair(settings(), capture());

        // Field transformations (two checks, each compile-checked), then the analyses and stages. Close
        // injection and owning-field repair changed nothing, so no analysis ran after them.
        assertThat(tools.ran()).containsExactly("java-settings", "java-version", "error-prone", "javac", "error-prone", "javac",
                "javac", "wpi", "wpi", "rlc", "close-injector", "owning-field-fixer", "rlfixer", "rlpatcher",
                "javac", "wpi", "wpi", "rlc");
        JsonNode report = Json.read(temp.resolve("out/report.json"));
        assertThat(report.get("success").asBoolean()).isTrue();
        assertThat(labels(report.get("analysis_runs"))).containsExactly("initial", "final");
        assertThat(report.at("/leaks/summary/fixed").asInt()).isEqualTo(1);
        assertThat(report.at("/leaks/summary/remaining").asInt()).isZero();
        assertThat(report.at("/leaks/warnings/0/fixed_by").asText()).isEqualTo("rlpatcher");
        assertThat(printed.toString(StandardCharsets.UTF_8)).startsWith("Resource leaks: 1 found, 1 fixed, 0 remaining");

        // The project itself is untouched until the bundle is applied.
        assertThat(project.resolve("src/demo/Demo.java")).hasContent(SOURCE.strip());
        BundleApplier.apply(project, temp.resolve("out/patches"), temp.resolve("out/logs/apply.log"), false);
        assertThat(Files.readString(project.resolve("src/demo/Demo.java"))).isEqualTo(FIXED);
    }

    @Test
    void aStageThatChangesTheSourcesIsFollowedByAnAnalysis() throws Exception {
        tools.diagnostics.add(LEAK);
        tools.diagnostics.add("");
        tools.closeInjector = FakeTools.writesPatch(root -> "--- src/demo/Demo.java\n+++ src/demo/Demo.java\n@@ -1 +1,2 @@\n package demo;\n"
                + "+// closed\n");

        engine().repair(settings(), capture());

        JsonNode report = Json.read(temp.resolve("out/report.json"));
        assertThat(labels(report.get("analysis_runs"))).containsExactly("initial", "post_close_injector");
        assertThat(report.at("/leaks/warnings/0/fixed_by").asText()).isEqualTo("close_injector");
        assertThat(report.at("/leaks/warnings/0/fix_patch").asText()).endsWith("stages/close_injector/close_injector.patch");
        // Nothing was left for RLFixer.
        assertThat(Files.readString(temp.resolve("out/stages/rlfixer/inputs/warnings.txt"))).isEmpty();
    }

    @Test
    void aCrashingToolFailsTheRunAndTheReportSaysWhy() throws Exception {
        tools.diagnostics.add(LEAK);
        tools.closeInjector = FakeTools::crash;

        assertThatThrownBy(() -> engine().repair(settings(), capture())).isInstanceOf(RunFailedException.class)
                .hasMessageStartingWith("Close injector failed for ");

        JsonNode report = Json.read(temp.resolve("out/report.json"));
        assertThat(report.get("success").asBoolean()).isFalse();
        assertThat(report.get("error_type").asText()).isEqualTo("StageException");
        assertThat(stages(report.get("executed_stages"))).containsExactly("field_transformations");
        JsonNode lastTiming = report.get("stage_timings").get(report.get("stage_timings").size() - 1);
        assertThat(lastTiming.get("stage").asText()).isEqualTo("close_injector");
        assertThat(lastTiming.get("success").asBoolean()).isFalse();
        assertThat(report.has("leaks")).isFalse();
        assertThat(Files.readString(temp.resolve("out/stages/close_injector/stage.log"))).contains("StackOverflowError");
    }

    @Test
    void analyzeRunsTheCheckerOnceWithoutInference() throws Exception {
        tools.diagnostics.add(LEAK);
        RunSettings settings = new RunSettings("analyze", project, temp.resolve("out"), false, Timeouts.NONE, FieldTransformationMode.RESOURCES,
                List.of(), Optional.empty());

        engine().analyze(settings, capture());

        assertThat(tools.ran()).containsExactly("java-settings", "java-version", "javac", "rlc");
        JsonNode report = Json.read(temp.resolve("out/report.json"));
        assertThat(report.at("/final_analysis/warning_count").asInt()).isEqualTo(1);
        assertThat(Files.readString(temp.resolve("out/logs/initial/wpi.log"))).startsWith("SKIPPED");
    }

    private static List<String> labels(JsonNode runs) {
        List<String> labels = new ArrayList<>();
        runs.forEach(run -> labels.add(run.get("label").asText()));
        return labels;
    }

    private static List<String> stages(JsonNode stages) {
        List<String> names = new ArrayList<>();
        stages.forEach(stage -> names.add(stage.get("stage").asText()));
        return names;
    }
}
