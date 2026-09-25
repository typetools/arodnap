package org.arodnap.cli.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.arodnap.cli.Main;
import org.arodnap.engine.json.Json;
import org.arodnap.engine.pipeline.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Real repairs with the real toolchain: the project's build, whole-program inference, the Resource
 * Leak Checker, the Java repair tools, RLFixer and RLPatcher. Nothing is mocked. Each test repairs a
 * copy of a fixture, checks the report, applies the patch and compiles the result with the project's
 * own build. They take about half a minute each, so they are opt-in:
 *
 * <pre>ARODNAP_E2E=1 mvn verify</pre>
 *
 * They need a JDK 17 or newer and {@code gradle}, {@code mvn} and {@code ant} on PATH (a test whose
 * tool is missing is skipped).
 */
@EnabledIfEnvironmentVariable(named = "ARODNAP_E2E", matches = "1")
class RealRepairTest {
    private static final Path FIXTURES = locateFixtures();
    private static final List<String> GRADLE = List.of("gradle", "--no-daemon", "--console=plain", "-q", "compileJava");

    private final List<Path> temporary = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        temporary.forEach(Workspace::deleteTree);
    }

    private record Repaired(JsonNode report, List<String> bundleFiles, Path repoRoot, Path outDir) {}

    @Test
    void theBaselineFixtureIsRepairedAppliedAndStillCompiles() throws Exception {
        requires("gradle");
        Repaired repaired = repairAndApply("gradle-pipeline-baseline", GRADLE, List.of());

        String fixture = "src/main/java/com/arodnap/fixture";
        assertThat(repaired.bundleFiles()).containsExactlyInAnyOrder(fixture + "/DirectLeakExample.java", fixture + "/TryCatchLeakExample.java",
                fixture + "/WrapperMissingClose.java", fixture + "/OwningFieldReassignment.java");
        assertThat(read(repaired, fixture + "/DirectLeakExample.java")).contains("try (");
        assertThat(read(repaired, fixture + "/TryCatchLeakExample.java")).contains("try (");
        assertThat(read(repaired, fixture + "/WrapperMissingClose.java")).contains("implements AutoCloseable");
    }

    @Test
    void aLeakThroughADependencyIsRepaired() throws Exception {
        requires("gradle");
        Repaired repaired = repairAndApply("gradle-dependency-leak", GRADLE, List.of());

        String source = "src/main/java/com/arodnap/fixture/DependencyLeakExample.java";
        assertThat(repaired.bundleFiles()).containsExactly(source);
        assertThat(read(repaired, source)).contains("try (FileInputStream in = new FileInputStream(path))");
    }

    @Test
    void everyStageDoesItsPart() throws Exception {
        requires("gradle");
        Repaired repaired = repairAndApply("gradle-pipeline-coverage", GRADLE, List.of());

        // Exact counts: a change here means a stage (or an analysis dependency) behaves differently.
        Map<String, Integer> counts = new LinkedHashMap<>();
        repaired.report().get("analysis_runs").forEach(run -> counts.put(run.get("label").asText(), run.get("warning_count").asInt()));
        assertThat(counts).containsExactly(Map.entry("initial", 26), Map.entry("post_close_injector", 26), Map.entry("post_owning_field", 24),
                Map.entry("final", 6));
        Map<String, JsonNode> stages = new LinkedHashMap<>();
        for (String name : List.of("close_injector", "owning_field", "rlfixer", "rlpatcher", "bundle")) {
            stages.put(name, Json.read(repaired.outDir().resolve("stages").resolve(name).resolve("stage_result.json")));
        }
        assertThat(strings(stages.get("close_injector").get("changed_files"))).containsExactly("src/main/java/wrapper/Wrapper.java");
        assertThat(strings(stages.get("owning_field").get("changed_files"))).containsExactlyInAnyOrder(
                "src/main/java/owning/PackagePrivateSink.java", "src/main/java/owning/PublicSink.java");
        assertThat(strings(stages.get("rlfixer").get("notes"))).containsExactly("RLFixer proposed 20 fix(es) for 24 leak warning(s).");
        assertThat(stages.get("rlpatcher").get("notes").get(0).asText()).contains("RLPatcher materialized 18 of 20");
        Set<String> changedByStages = new HashSet<>();
        for (String name : List.of("close_injector", "owning_field", "rlpatcher")) {
            changedByStages.addAll(strings(stages.get(name).get("changed_files")));
        }
        assertThat(repaired.bundleFiles()).containsExactlyInAnyOrderElementsOf(changedByStages);

        // Per-leak results: the counts reconcile with the analysis runs above.
        JsonNode leaks = repaired.report().get("leaks");
        JsonNode summary = leaks.get("summary");
        assertThat(List.of(summary.get("initial").asInt(), summary.get("found_during_repair").asInt(), summary.get("fixed").asInt(),
                summary.get("remaining").asInt())).containsExactly(26, 1, 21, 6);
        assertThat(summary.get("fixed_by_stage").get("close_injector").asInt()).isEqualTo(1);
        assertThat(summary.get("fixed_by_stage").get("owning_field").asInt()).isEqualTo(2);
        assertThat(summary.get("fixed_by_stage").get("rlpatcher").asInt()).isEqualTo(18);
        assertThat(Path.of(leaks.get("html_report").asText())).isRegularFile();
        Map<String, JsonNode> byFile = new LinkedHashMap<>();
        leaks.get("warnings").forEach(warning -> byFile.put(warning.get("file").asText(), warning));
        assertThat(byFile.get("src/main/java/wrapper/Wrapper.java").get("fixed_by").asText()).isEqualTo("close_injector");
        JsonNode client = byFile.get("src/main/java/wrapper/WrapperClient.java");
        assertThat(List.of(client.get("first_seen").asText(), client.get("fixed_by").asText())).containsExactly("post_close_injector", "rlpatcher");

        // The paper's scenario: the leak in the wrapper's client is fixed once the wrapper is closable.
        assertThat(read(repaired, "src/main/java/wrapper/WrapperClient.java")).contains("try (Wrapper wrapper = new Wrapper(path))");
        assertThat(read(repaired, "src/main/java/owning/PackagePrivateSink.java")).contains("\n    private final FileWriter out;\n");
    }

    @Test
    void everyModuleOfAMultiModuleGradleBuildIsRepaired() throws Exception {
        requires("gradle");
        Repaired repaired = repairAndApply("gradle-multimodule", GRADLE, List.of());

        String core = "core/src/main/java/demo/core/FirstByte.java";
        String app = "app/src/main/java/demo/app/Report.java";
        assertThat(repaired.bundleFiles()).containsExactlyInAnyOrder(core, app);
        assertThat(read(repaired, core)).contains("try (FileInputStream in = new FileInputStream(path))");
        assertThat(read(repaired, app)).contains("try (FileInputStream in = new FileInputStream(path))");
    }

    @Test
    void aMavenProjectIsRepaired() throws Exception {
        requires("mvn");
        Repaired repaired = repairAndApply("maven-dependency-leak", List.of("mvn", "-q", "-B", "compile"), List.of());

        String source = "src/main/java/demo/ReadAll.java";
        assertThat(repaired.bundleFiles()).containsExactly(source);
        assertThat(read(repaired, source)).contains("try (FileInputStream in = new FileInputStream(path))");
    }

    @Test
    void anAntProjectWithAVendoredJarIsRepaired() throws Exception {
        requires("ant");
        Repaired repaired = repairAndApply("ant-vendored-jar", List.of("ant", "-q", "compile"), List.of());

        String source = "src/demo/Checksum.java";
        assertThat(repaired.bundleFiles()).containsExactly(source);
        assertThat(read(repaired, source)).contains("try (FileInputStream in = new FileInputStream(path))");
    }

    @Test
    void aProjectBuiltByAJavacScriptIsRepaired() throws Exception {
        Repaired repaired = repairAndApply("javac-script", List.of("./build.sh"), List.of("./build.sh"));

        String source = "src/demo/FirstByte.java";
        assertThat(repaired.bundleFiles()).containsExactly(source);
        assertThat(read(repaired, source)).contains("try (FileInputStream in = new FileInputStream(path))");
    }

    @Test
    void aLeakReturnedThroughACycleOfCallersIsLeftUnfixed() throws Exception {
        Repaired repaired = repairAndApply("javac-return-cycle", List.of("./build.sh"), List.of("./build.sh"));

        assertThat(repaired.bundleFiles()).containsExactly("src/demo/FirstByte.java");
        List<String> remaining = new ArrayList<>();
        repaired.report().at("/leaks/warnings").forEach(leak -> {
            if (leak.get("status").asText().equals("remaining")) {
                remaining.add(leak.get("file").asText() + " " + leak.get("reason").asText());
            }
        });
        assertThat(remaining).containsExactly("src/demo/Cycle.java rlfixer_unfixable");
    }

    @Test
    void resourceFieldsAreMadeFinalOrLocalBeforeAnalysis() throws Exception {
        Repaired repaired = repairAndApply("javac-field-transformations", List.of("./build.sh"), List.of("./build.sh"));

        List<String> changes = new ArrayList<>();
        repaired.report().at("/leaks/field_changes/changes").forEach(change -> changes.add(
                change.get("file").asText() + " " + change.get("field").asText() + " " + change.get("change").asText()));
        assertThat(changes).containsExactlyInAnyOrder("src/demo/TempFileWriter.java stream final", "src/demo/Server.java socket final",
                "src/demo/LineCounter.java reader local");
        String writer = read(repaired, "src/demo/TempFileWriter.java");
        assertThat(writer).contains("private final PrintStream stream;");
        assertThat(writer).as("a field that holds no resource is left alone").contains("private String path;");
        String server = read(repaired, "src/demo/Server.java");
        assertThat(server).contains("ServerSocket tempSocket = null;").contains("this.socket = tempSocket;");
        assertThat(read(repaired, "src/demo/LineCounter.java")).doesNotContain("private BufferedReader reader;");
        assertThat(repaired.report().at("/leaks/summary/remaining").asInt()).isZero();
    }

    @Test
    void aLatin1Java8ProjectIsAnalyzedWithTheBuildsEncodingAndRelease() throws Exception {
        Path root = Files.createTempDirectory("arodnap-e2e-");
        temporary.add(root);
        Path repo = root.resolve("javac-latin1");
        Workspace.copyDirectory(FIXTURES.resolve("javac-latin1"), repo);
        Workspace.deleteTree(repo.resolve("out"));

        Path inferOut = root.resolve("infer-out");
        assertThat(main("infer", "--out-dir", inferOut.toString(), repo.toString(), "--", "./build.sh")).isZero();
        JsonNode report = Json.read(inferOut.resolve("report.json"));
        assertThat(report.get("success").asBoolean()).as(report.path("error").asText()).isTrue();
        JsonNode run = report.get("analysis_runs").get(0);
        JsonNode metadata = Json.read(Path.of(run.get("adapter_metadata_path").asText()));
        assertThat(List.of(metadata.get("release").asInt(), metadata.get("encoding").asText())).containsExactly(8, "ISO-8859-1");
        assertThat(run.get("warning_count").asInt()).isPositive();

        // The repair tools read sources as UTF-8, so repair stops with a clear message.
        Path repairOut = root.resolve("repair-out");
        Map<String, byte[]> original = snapshot(repo);
        assertThat(main("repair", "--out-dir", repairOut.toString(), repo.toString(), "--", "./build.sh")).isEqualTo(1);
        JsonNode failed = Json.read(repairOut.resolve("report.json"));
        assertThat(failed.get("success").asBoolean()).isFalse();
        assertThat(failed.get("error").asText()).contains("is not valid UTF-8");
        assertThat(snapshot(repo)).containsExactlyInAnyOrderEntriesOf(original);
    }

    private Repaired repairAndApply(String fixture, List<String> verify, List<String> buildCommand) throws Exception {
        Path root = Files.createTempDirectory("arodnap-e2e-");
        temporary.add(root);
        Path repo = root.resolve(fixture);
        Workspace.copyDirectory(FIXTURES.resolve(fixture), repo);
        for (String generated : List.of("build", ".gradle", "target", "out")) {
            try (Stream<Path> paths = Files.walk(repo)) {
                paths.filter(path -> path.getFileName().toString().equals(generated) && Files.isDirectory(path)).toList()
                        .forEach(Workspace::deleteTree);
            }
        }
        Path out = root.resolve("arodnap-out");
        Map<String, byte[]> original = snapshot(repo);

        List<String> args = new ArrayList<>(List.of("repair", "--out-dir", out.toString(), repo.toString()));
        if (!buildCommand.isEmpty()) {
            args.add("--");
            args.addAll(buildCommand);
        }
        assertThat(main(args.toArray(String[]::new))).isZero();
        JsonNode report = Json.read(out.resolve("report.json"));
        assertThat(report.get("success").asBoolean()).as(report.path("error").asText()).isTrue();
        JsonNode runs = report.get("analysis_runs");
        assertThat(runs.get(runs.size() - 1).get("warning_count").asInt()).as("no warnings were repaired")
                .isLessThan(runs.get(0).get("warning_count").asInt());
        assertThat(snapshot(repo)).as("repair must not modify the original repository").containsExactlyInAnyOrderEntriesOf(original);

        JsonNode patches = Json.read(out.resolve("patches/manifest.json")).get("patches");
        assertThat(patches).hasSize(1);
        assertThat(main("apply", "--out-dir", out.toString(), "--patch-dir", out.resolve("patches").toString(), repo.toString())).isZero();
        Process compile = new ProcessBuilder(verify).directory(repo.toFile()).redirectErrorStream(true).start();
        String output = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(compile.waitFor()).as(output).isZero();
        return new Repaired(report, strings(patches.get(0).get("changed_files")), repo, out);
    }

    private static int main(String... args) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        int code = Main.run(args, stream, stream);
        if (code != 0) {
            System.err.println(output.toString(StandardCharsets.UTF_8));
        }
        return code;
    }

    private static String read(Repaired repaired, String file) throws IOException {
        return Files.readString(repaired.repoRoot().resolve(file));
    }

    private static List<String> strings(JsonNode array) {
        List<String> strings = new ArrayList<>();
        array.forEach(item -> strings.add(item.asText()));
        return strings;
    }

    private static Map<String, byte[]> snapshot(Path root) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(path -> path.toString().endsWith(".java") && Files.isRegularFile(path)).sorted().toList()) {
                files.put(root.relativize(path).toString(), Files.readAllBytes(path));
            }
        }
        return files;
    }

    private static void requires(String tool) {
        boolean found = Stream.of(System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator))
                .anyMatch(directory -> Files.isExecutable(Path.of(directory, tool)));
        assumeTrue(found, tool + " is not on PATH");
    }

    private static Path locateFixtures() {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            Path fixtures = directory.resolve("tests").resolve("fixtures");
            if (Files.isDirectory(fixtures.resolve("javac-script"))) {
                return fixtures;
            }
        }
        throw new IllegalStateException("Cannot find the test fixtures (tests/fixtures).");
    }
}
