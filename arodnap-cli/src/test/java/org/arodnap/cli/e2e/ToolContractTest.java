package org.arodnap.cli.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.arodnap.cli.Installation;
import org.arodnap.engine.analysis.Analysis;
import org.arodnap.engine.analysis.Analyzer;
import org.arodnap.engine.diagnostics.CheckerWarning;
import org.arodnap.engine.diagnostics.Diagnostic;
import org.arodnap.engine.files.FilePaths;
import org.arodnap.engine.inputs.ProjectCapture.CapturedProject;
import org.arodnap.engine.jdk.JdkLocator;
import org.arodnap.engine.json.Json;
import org.arodnap.engine.pipeline.OutputLayout;
import org.arodnap.engine.pipeline.RunContext;
import org.arodnap.engine.pipeline.RunSettings;
import org.arodnap.engine.pipeline.RunSettings.FieldTransformationMode;
import org.arodnap.engine.pipeline.Timeouts;
import org.arodnap.engine.pipeline.Workspace;
import org.arodnap.engine.process.CommandRunner;
import org.arodnap.engine.stages.CloseInjectionStage;
import org.arodnap.engine.stages.OwningFieldStage;
import org.arodnap.engine.stages.RlFixerStage;
import org.arodnap.engine.stages.RlPatcherStage;
import org.arodnap.engine.stages.StageContext;
import org.arodnap.engine.tools.CheckerFramework;
import org.arodnap.engine.tools.RlFixerFormats;
import org.arodnap.engine.tools.Toolchain;
import org.arodnap.model.BuildDescription;
import org.arodnap.model.CompileUnit;
import org.arodnap.model.ProjectInputs;
import org.arodnap.model.StageResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Each real tool, on a small program, produces what the engine's adapter for it reads: the Checker
 * Framework's structured warnings, the patches of AutoCloseInjector and OwningFieldFixer, RLFixer's
 * report and debug table, and RLPatcher's patch and messages. When a tool is upgraded or changed,
 * this is where a format mismatch shows up. The tools run in pipeline order on one workspace.
 */
@EnabledIfEnvironmentVariable(named = "ARODNAP_E2E", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ToolContractTest {
    private static final String LEAKY = """
            package demo;

            import java.io.FileInputStream;
            import java.io.IOException;

            public class Leaky {
                public int first(String path) throws IOException {
                    FileInputStream in = new FileInputStream(path);
                    return in.read();
                }
            }
            """;
    private static final String WRAPPER = """
            package wrapper;

            import java.io.FileInputStream;
            import java.io.IOException;
            import java.io.InputStream;

            /** A resource wrapper that owns a stream but has no close() method. */
            public class Wrapper {
                private final InputStream in;

                public Wrapper(String path) throws IOException {
                    this.in = new FileInputStream(path);
                }

                public int read() throws IOException {
                    return in.read();
                }
            }
            """;
    private static final String SINK = """
            package owning;

            import java.io.Closeable;
            import java.io.FileWriter;
            import java.io.IOException;

            /** Owning field that is public and non-final but only assigned in the constructor. */
            public class PublicSink implements Closeable {
                public FileWriter out;

                public PublicSink(String path) throws IOException {
                    out = new FileWriter(path);
                }

                public void write(String text) throws IOException {
                    out.write(text);
                }

                @Override
                public void close() throws IOException {
                    out.close();
                }
            }
            """;

    private Path project;
    private Workspace workspace;
    private RunContext context;
    private Analysis analysis;
    private final List<StageResult> results = new ArrayList<>();

    @BeforeAll
    void analyze() throws Exception {
        project = Files.createTempDirectory("arodnap-contract-").resolve("contract");
        for (String[] file : new String[][] {{"src/demo/Leaky.java", LEAKY}, {"src/wrapper/Wrapper.java", WRAPPER}, {"src/owning/PublicSink.java", SINK}}) {
            Files.createDirectories(project.resolve(file[0]).getParent());
            Files.writeString(project.resolve(file[0]), file[1]);
        }
        Toolchain toolchain = Installation.locate(Optional.empty()).toolchain();
        CommandRunner runner = CommandRunner.processes();
        JdkLocator jdks = JdkLocator.fromEnvironment(runner);
        workspace = Workspace.copyOf(project, false);
        List<Path> sources = new ArrayList<>();
        for (String file : List.of("src/demo/Leaky.java", "src/wrapper/Wrapper.java", "src/owning/PublicSink.java")) {
            sources.add(FilePaths.real(workspace.resolve(file)));
        }
        CompileUnit unit = new CompileUnit(workspace.root(), sources, List.of(), Optional.empty(), Optional.empty(), Optional.of(17),
                Optional.of("UTF-8"), List.of(), List.of(), Optional.empty());
        ProjectInputs inputs = new ProjectInputs(List.of(unit), sources, List.of(), List.of(), Optional.of(17), Optional.of("UTF-8"),
                FilePaths.real(workspace.resolve("src")));
        CapturedProject captured = new CapturedProject(inputs,
                new BuildDescription("command", "command", workspace.root(), List.of(), "command", "", List.of()), List.of());
        Path out = project.getParent().resolve("out");
        RunSettings settings = new RunSettings("repair", project, out, false, Timeouts.NONE, FieldTransformationMode.RESOURCES, List.of(),
                Optional.empty());
        OutputLayout layout = new OutputLayout(out);
        layout.create();
        context = new RunContext(settings, toolchain, runner, CheckerFramework.analysisJdk(toolchain, jdks), workspace, layout, captured);
        analysis = new Analyzer(context).analyze("initial", true);
    }

    @AfterAll
    void cleanUp() {
        workspace.close();
        Workspace.deleteTree(project.getParent());
    }

    private StageResult run(org.arodnap.engine.stages.Stage stage) throws Exception {
        StageResult result = stage.run(new StageContext(context, analysis, results));
        results.add(result);
        return result;
    }

    @Test
    @Order(1)
    void theCheckerFrameworkReportsLeaksWithStructuredFields() throws Exception {
        String diagnostics = Files.readString(analysis.diagnostics());
        List<Diagnostic> leaks = Diagnostic.parseAll(diagnostics, workspace.root()).stream().filter(Diagnostic::isLeak).toList();

        Diagnostic leaky = leaks.stream().filter(leak -> leak.path().equals("src/demo/Leaky.java")).findFirst().orElseThrow();
        assertThat(leaky.line()).isEqualTo(8);
        assertThat(leaky.fields()).hasSize(4);
        assertThat(leaky.fields().subList(0, 3)).containsExactly("method close", "in", "java.io.FileInputStream");
        assertThat(leaky.text()).containsPattern("\\( \\d+, \\d+ \\)");
        assertThat(Files.list(analysis.inferenceDirectory().resolve("demo"))).isNotEmpty();
    }

    @Test
    @Order(2)
    void autoCloseInjectorWritesAPatchTheEngineCanApply() throws Exception {
        StageResult result = run(new CloseInjectionStage());

        assertThat(result.changedFiles()).containsExactly("src/wrapper/Wrapper.java");
        assertThat(Files.readString(Path.of(result.artifacts().get("patch")))).startsWith("--- src/wrapper/Wrapper.java");
        assertThat(Files.readString(workspace.resolve("src/wrapper/Wrapper.java"))).contains("implements AutoCloseable").contains("close()");
    }

    @Test
    @Order(3)
    void owningFieldFixerWritesAPatchTheEngineCanApply() throws Exception {
        StageResult result = run(new OwningFieldStage());

        assertThat(result.changedFiles()).containsExactly("src/owning/PublicSink.java");
        assertThat(Files.readString(workspace.resolve("src/owning/PublicSink.java"))).contains("public final FileWriter out;");
    }

    @Test
    @Order(4)
    void rlfixerReportsFixesAndADebugTableTheEngineCanRead() throws Exception {
        StageResult result = run(new RlFixerStage());

        String fixes = Files.readString(Path.of(result.artifacts().get("fixes")));
        assertThat(fixes).contains(RlFixerFormats.SOURCE_LEVEL_FIXES_MARKER);
        List<RlFixerFormats.FixSuggestion> suggestions = RlFixerFormats.parseFixes(fixes, context.inputs().sourceRoot());
        assertThat(suggestions).extracting(RlFixerFormats.FixSuggestion::relpath).contains("demo/Leaky.java");
        var table = RlFixerFormats.parseDebugTable(Files.readString(Path.of(result.artifacts().get("debug"))));
        assertThat(table.fixable()).contains(new RlFixerFormats.Key("demo/Leaky.java", 8));
        assertThat(CheckerWarning.parseAll(Files.readString(analysis.diagnostics()))).isNotEmpty();
    }

    @Test
    @Order(5)
    void rlpatcherMaterializesTheFix() throws Exception {
        StageResult result = run(new RlPatcherStage());

        JsonNode manifest = Json.read(Path.of(result.artifacts().get("patch_manifest")));
        List<String> outcomes = new ArrayList<>();
        manifest.get("fixes").forEach(fix -> outcomes.add(fix.get("file").asText() + " " + fix.get("outcome").asText()));
        assertThat(outcomes).contains("demo/Leaky.java materialized");
        assertThat(result.changedFiles()).contains("src/demo/Leaky.java");
        assertThat(Files.readString(workspace.resolve("src/demo/Leaky.java"))).contains("try (FileInputStream in = new FileInputStream(path))");
    }
}
