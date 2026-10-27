package org.arodnap.cli.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.arodnap.engine.files.FilePaths;
import org.arodnap.engine.inputs.ProjectCapture;
import org.arodnap.engine.inputs.UnsupportedProjectException;
import org.arodnap.engine.jdk.JdkLocator;
import org.arodnap.engine.pipeline.Timeouts;
import org.arodnap.engine.process.CommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildRecordingsTest {
    @TempDir
    Path temp;

    private ProjectCapture select(List<String> buildCommand) {
        CommandRunner runner = CommandRunner.processes();
        BuildRecording.Hooks hooks = new BuildRecording.Hooks(temp.resolve("tools/ant-capture.jar"), temp.resolve("tools/maven-capture.jar"),
                recorderClasspath(), Path.of(System.getProperty("java.home"), "bin", "java"));
        return BuildRecordings.select(hooks, new BuildRecording.Options(List.of(), Optional.empty(), buildCommand, Timeouts.NONE), runner,
                JdkLocator.fromEnvironment(runner));
    }

    private static Path recorderClasspath() {
        try {
            return Path.of(JavacRecorder.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void detectsGradleBeforeMavenBeforeAnt() throws Exception {
        Files.writeString(temp.resolve("build.xml"), "<project/>");
        assertThat(select(List.of()).detect(temp).buildSystem()).isEqualTo("ant");
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        assertThat(select(List.of()).detect(temp).buildSystem()).isEqualTo("maven");
        Files.writeString(temp.resolve("settings.gradle"), "");
        assertThat(select(List.of()).detect(temp).buildSystem()).isEqualTo("gradle");
    }

    @Test
    void aBuildCommandPicksTheRecordingForItsTool() throws Exception {
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        assertThat(select(List.of("./mvnw", "-Pci", "compile")).detect(temp).buildSystem()).isEqualTo("maven");
        assertThat(select(List.of("./build.sh")).detect(temp).buildSystem()).isEqualTo("command");
    }

    @Test
    void aProjectWithoutABuildFileNeedsABuildCommand() {
        assertThatThrownBy(() -> select(List.of()).detect(temp)).isInstanceOf(UnsupportedProjectException.class)
                .hasMessageContaining("No Gradle, Maven or Ant build file found").hasMessageContaining("-- ./build.sh");
    }

    @Test
    void recordsTheJavacCallsOfABuildScript() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Files.createDirectories(project.resolve("src/demo"));
        Files.writeString(project.resolve("src/demo/A.java"), "package demo; class A {}\n");
        Files.writeString(project.resolve("build.sh"), "#!/bin/sh\nset -e\nmkdir -p out\njavac --release 11 -encoding UTF-8 -d out src/demo/A.java\n");
        project.resolve("build.sh").toFile().setExecutable(true);
        Path state = Files.createDirectories(temp.resolve("state"));

        ProjectCapture.CapturedProject captured = select(List.of("./build.sh")).capture(project, state);

        assertThat(captured.inputs().sources()).containsExactly(FilePaths.real(project.resolve("src/demo/A.java")));
        assertThat(captured.inputs().release()).contains(11);
        assertThat(captured.inputs().encoding()).contains("UTF-8");
        assertThat(captured.build().buildCommand()).containsExactly("./build.sh");
        assertThat(captured.build().buildToolSource()).isEqualTo("command");
        // The recording lives next to the project, never inside it.
        assertThat(captured.logs()).allSatisfy(log -> assertThat(log.startsWith(state)).isTrue());
        assertThat(project.resolve("out/demo/A.class")).exists();
        assertThat(Files.list(project).map(path -> path.getFileName().toString())).containsExactlyInAnyOrder("src", "build.sh", "out");
    }

    @Test
    void aFailingBuildIsReportedWithItsOutput() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Files.writeString(project.resolve("build.sh"), "#!/bin/sh\necho 'cannot find symbol' >&2\nexit 3\n");
        project.resolve("build.sh").toFile().setExecutable(true);

        assertThatThrownBy(() -> select(List.of("./build.sh")).capture(project, Files.createDirectories(temp.resolve("state"))))
                .isInstanceOf(UnsupportedProjectException.class).hasMessageContaining("The build failed (exit code 3)")
                .hasMessageContaining("cannot find symbol");
    }

}
