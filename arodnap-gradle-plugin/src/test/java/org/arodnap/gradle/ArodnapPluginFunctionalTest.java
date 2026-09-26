package org.arodnap.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * The plugin on a real multi-project build, with the real tools from the local Maven repository
 * ({@code mvn install} at the repository root first). Opt-in like the other end-to-end tests:
 *
 * <pre>ARODNAP_E2E=1 gradle test</pre>
 */
@EnabledIfEnvironmentVariable(named = "ARODNAP_E2E", matches = "1")
class ArodnapPluginFunctionalTest {
    private static final Path TEST_PROJECTS = Path.of(System.getProperty("arodnap.testProjects"));
    private static final String CORE = "core/src/main/java/demo/core/FirstByte.java";
    private static final String APP = "app/src/main/java/demo/app/Report.java";

    @TempDir
    Path project;

    @Test
    void repairsEveryProjectAppliesThePatchAndTheBuildStillCompiles() throws IOException {
        copy(TEST_PROJECTS.resolve("gradle-multimodule"), project);
        Path buildFile = project.resolve("build.gradle");
        Files.writeString(buildFile, "plugins {\n    id 'org.arodnap'\n}\n\nrepositories {\n    mavenLocal()\n    mavenCentral()\n}\n\n"
                + Files.readString(buildFile));
        String original = Files.readString(project.resolve(CORE));

        BuildResult repair = gradle("arodnapRepair").build();
        assertThat(repair.task(":arodnapRepair").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(repair.task(":core:compileJava").getOutcome()).isIn(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE);
        Path out = project.resolve("build/arodnap");
        JsonNode report = new ObjectMapper().readTree(out.resolve("report.json").toFile());
        assertThat(report.get("success").asBoolean()).as(report.path("error").asText()).isTrue();
        assertThat(report.at("/leaks/summary/fixed").asInt()).isEqualTo(2);
        List<String> changed = new ArrayList<>();
        new ObjectMapper().readTree(out.resolve("patches/manifest.json").toFile()).at("/patches/0/changed_files")
                .forEach(file -> changed.add(file.asText()));
        assertThat(changed).containsExactlyInAnyOrder(CORE, APP);
        assertThat(Files.readString(project.resolve(CORE))).as("repair leaves the project alone").isEqualTo(original);

        gradle("arodnapApply").build();
        for (String source : List.of(CORE, APP)) {
            assertThat(Files.readString(project.resolve(source))).contains("try (FileInputStream in = new FileInputStream(path))");
        }
        assertThat(gradle("compileJava").build().task(":app:compileJava").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
    }

    private GradleRunner gradle(String task) {
        return GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath().withArguments(task, "--stacktrace")
                .forwardStdError(new java.io.PrintWriter(System.err, true, StandardCharsets.UTF_8));
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
