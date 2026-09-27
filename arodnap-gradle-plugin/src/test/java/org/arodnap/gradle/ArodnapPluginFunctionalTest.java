package org.arodnap.gradle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.gradle.util.GradleVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The plugin on real builds, with the real tools from the local Maven repository ({@code mvn install}
 * at the repository root first). Opt-in like the other end-to-end tests:
 *
 * <pre>ARODNAP_E2E=1 gradle test</pre>
 */
@EnabledIfEnvironmentVariable(named = "ARODNAP_E2E", matches = "1")
class ArodnapPluginFunctionalTest {
    /** The oldest Gradle the plugin is tested on. */
    static final String OLDEST_GRADLE = "8.14.4";
    private static final Path TEST_PROJECTS = Path.of(System.getProperty("arodnap.testProjects"));
    private static final String CORE = "core/src/main/java/demo/core/FirstByte.java";
    private static final String APP = "app/src/main/java/demo/app/Report.java";
    private static final String SOURCE = "src/main/java/demo/FirstByte.java";
    private static final String LEAKY = """
            package demo;

            import java.io.FileInputStream;
            import java.io.IOException;

            public class FirstByte {

                public int read(String path) throws IOException {
                    FileInputStream in = new FileInputStream(path);
                    return in.read();
                }
            }
            """;

    @TempDir
    Path project;

    static Stream<String> gradleVersions() {
        return Stream.of(OLDEST_GRADLE, GradleVersion.current().getVersion());
    }

    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("gradleVersions")
    void repairsEveryProjectAppliesThePatchAndTheBuildStillCompiles(String gradleVersion) throws IOException {
        assumeTrue(!gradleVersion.equals(OLDEST_GRADLE) || Runtime.version().feature() <= 24, "Gradle 8.14 runs on JDK 24 and older");
        copy(TEST_PROJECTS.resolve("gradle-multimodule"), project);
        Path buildFile = project.resolve("build.gradle");
        Files.writeString(buildFile, "plugins {\n    id 'org.arodnap'\n}\n\nrepositories {\n    mavenLocal()\n    mavenCentral()\n}\n\n"
                + Files.readString(buildFile));
        String original = Files.readString(project.resolve(CORE));

        BuildResult repair = gradle(gradleVersion, "arodnapRepair").build();
        assertThat(repair.task(":arodnapRepair").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(repair.task(":core:compileJava").getOutcome()).isIn(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE);
        Path out = project.resolve("build/arodnap");
        JsonNode report = json(out.resolve("report.json"));
        assertThat(report.get("success").asBoolean()).as(report.path("error").asText()).isTrue();
        assertThat(report.at("/leaks/summary/fixed").asInt()).isEqualTo(2);
        List<String> changed = new ArrayList<>();
        json(out.resolve("patches/manifest.json")).at("/patches/0/changed_files").forEach(file -> changed.add(file.asText()));
        assertThat(changed).containsExactlyInAnyOrder(CORE, APP);
        assertThat(Files.readString(project.resolve(CORE))).as("repair leaves the project alone").isEqualTo(original);

        gradle(gradleVersion, "arodnapApply").build();
        for (String source : List.of(CORE, APP)) {
            assertThat(Files.readString(project.resolve(source))).contains("try (FileInputStream in = new FileInputStream(path))");
        }
        assertThat(gradle(gradleVersion, "compileJava").build().task(":app:compileJava").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
    }

    @Test
    void aKotlinBuildsSettingsAreUsedAndApplyRefusesAFileChangedSinceTheRepair() throws IOException {
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"single\"\n");
        Files.writeString(project.resolve("build.gradle.kts"), """
                plugins {
                    java
                    id("org.arodnap")
                }

                repositories {
                    mavenLocal()
                    mavenCentral()
                }

                arodnap {
                    outputDirectory = layout.buildDirectory.dir("custom")
                    fieldTransformations = "off"
                }
                """);
        Files.createDirectories(project.resolve(SOURCE).getParent());
        Files.writeString(project.resolve(SOURCE), LEAKY);

        gradle(GradleVersion.current().getVersion(), "arodnapRepair").build();
        Path out = project.resolve("build/custom");
        JsonNode report = json(out.resolve("report.json"));
        assertThat(report.get("success").asBoolean()).as(report.path("error").asText()).isTrue();
        assertThat(report.at("/leaks/summary/fixed").asInt()).isEqualTo(1);
        assertThat(json(out.resolve("stages/field_transformations/stage_result.json")).at("/notes/0").asText())
                .isEqualTo("Field transformations are off.");
        assertThat(project.resolve("build/arodnap")).doesNotExist();

        String edited = LEAKY + "// edited after the repair\n";
        Files.writeString(project.resolve(SOURCE), edited);
        BuildResult apply = gradle(GradleVersion.current().getVersion(), "arodnapApply").buildAndFail();
        assertThat(apply.getOutput()).contains(SOURCE + " has changed since the patch was made");
        assertThat(Files.readString(project.resolve(SOURCE))).isEqualTo(edited);
    }

    @Test
    void aSourceTheBuildGeneratesIsReportedButNeverPatched() throws IOException {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'single'\n");
        Files.writeString(project.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'org.arodnap'
                }

                repositories {
                    mavenLocal()
                    mavenCentral()
                }

                // A code generator: writes build/generated/sources/demo, which is compiled with src/.
                def generateDemo = tasks.register('generateDemo', Copy) {
                    from 'templates'
                    into layout.buildDirectory.dir('generated/sources/demo')
                    rename { it.replace('.java.txt', '.java') }
                }
                sourceSets.main.java.srcDir(generateDemo)
                """);
        Files.createDirectories(project.resolve(SOURCE).getParent());
        Files.writeString(project.resolve(SOURCE), LEAKY);
        Files.createDirectories(project.resolve("templates/demo"));
        Files.writeString(project.resolve("templates/demo/Generated.java.txt"), LEAKY.replace("class FirstByte", "class Generated"));

        gradle(GradleVersion.current().getVersion(), "arodnapRepair").build();

        Path out = project.resolve("build/arodnap");
        JsonNode report = json(out.resolve("report.json"));
        assertThat(report.get("success").asBoolean()).as(report.path("error").asText()).isTrue();
        List<String> leaks = new ArrayList<>();
        report.at("/leaks/warnings").forEach(leak -> leaks.add(leak.get("file").asText() + " " + leak.get("status").asText()));
        assertThat(leaks).containsExactlyInAnyOrder(SOURCE + " fixed", "build/generated/sources/demo/demo/Generated.java remaining");
        assertThat(report.at("/leaks/summary/remaining_by_reason/generated").asInt()).isEqualTo(1);
        List<String> changed = new ArrayList<>();
        json(out.resolve("patches/manifest.json")).at("/patches/0/changed_files").forEach(file -> changed.add(file.asText()));
        assertThat(changed).containsExactly(SOURCE);
    }

    @Test
    void doctorAnalyzeAndInferEachWriteTheirOutput() throws IOException {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'single'\n");
        Files.writeString(project.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'org.arodnap'
                }

                repositories {
                    mavenLocal()
                    mavenCentral()
                }
                """);
        Files.createDirectories(project.resolve(SOURCE).getParent());
        Files.writeString(project.resolve(SOURCE), LEAKY);
        Path out = project.resolve("build/arodnap");
        String version = GradleVersion.current().getVersion();

        gradle(version, "arodnapDoctor").build();
        assertThat(json(out.resolve("doctor.json")).get("success").asBoolean()).isTrue();

        gradle(version, "arodnapAnalyze").build();
        JsonNode analyze = json(out.resolve("report.json"));
        assertThat(analyze.at("/run_metadata/command").asText()).isEqualTo("analyze");
        assertThat(analyze.at("/analysis_runs/0/warning_count").asInt()).isPositive();
        assertThat(Path.of(analyze.at("/analysis_runs/0/wpi_log_path").asText())).content().startsWith("SKIPPED");

        gradle(version, "arodnapInfer").build();
        JsonNode infer = json(out.resolve("report.json"));
        assertThat(infer.at("/run_metadata/command").asText()).isEqualTo("infer");
        assertThat(infer.get("success").asBoolean()).as(infer.path("error").asText()).isTrue();
        assertThat(Path.of(infer.at("/analysis_runs/0/wpi_log_path").asText())).content().doesNotStartWith("SKIPPED");
    }

    private GradleRunner gradle(String version, String task) {
        return GradleRunner.create().withGradleVersion(version).withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments(task, "--stacktrace").forwardStdError(new PrintWriter(System.err, true, StandardCharsets.UTF_8));
    }

    private static JsonNode json(Path file) throws IOException {
        return new ObjectMapper().readTree(file.toFile());
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path path : paths.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target);
                }
            }
        }
    }
}
