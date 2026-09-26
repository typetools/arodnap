package org.arodnap.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import org.arodnap.engine.tools.ToolCoordinates;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What applying the plugin sets up, without running anything. */
class ArodnapPluginTest {
    @TempDir
    File directory;

    @Test
    void registersItsTasksWithDefaults() {
        Project project = ProjectBuilder.builder().withProjectDir(directory).build();
        project.getPluginManager().apply("org.arodnap");

        for (String task : new String[] {"arodnapRepair", "arodnapAnalyze", "arodnapInfer", "arodnapDoctor", "arodnapApply"}) {
            assertThat(project.getTasks().getByName(task).getGroup()).isEqualTo("arodnap");
        }
        ArodnapExtension extension = project.getExtensions().getByType(ArodnapExtension.class);
        assertThat(extension.getOutputDirectory().get().getAsFile()).isEqualTo(new File(project.getLayout().getBuildDirectory().get().getAsFile(), "arodnap"));
        assertThat(extension.getFieldTransformations().get()).isEqualTo("resources");
        RunTask repair = (RunTask) project.getTasks().getByName("arodnapRepair");
        assertThat(repair.getCommand().get()).isEqualTo("repair");
    }

    @Test
    void theToolsAreTheEnginesArtifacts() {
        Project project = ProjectBuilder.builder().withProjectDir(directory).build();
        project.getPluginManager().apply("org.arodnap");
        RunTask repair = (RunTask) project.getTasks().getByName("arodnapRepair");

        assertThat(repair.getTools()).containsOnlyKeys(ToolCoordinates.TOOLCHAIN);
        Dependency rlfixer = project.getConfigurations().getByName("arodnap_rlfixer").getDependencies().iterator().next();
        assertThat(rlfixer.getGroup() + ":" + rlfixer.getName() + ":" + rlfixer.getVersion()).isEqualTo(ToolCoordinates.of("rlfixer"));
    }

    @Test
    void readsTheMainCompileTaskOfEveryProject() {
        Project root = ProjectBuilder.builder().withProjectDir(directory).build();
        Project core = ProjectBuilder.builder().withName("core").withParent(root).build();
        Project app = ProjectBuilder.builder().withName("app").withParent(root).build();
        root.getPluginManager().apply("org.arodnap");
        core.getPluginManager().apply("java");
        app.getPluginManager().apply("java");

        RunTask repair = (RunTask) root.getTasks().getByName("arodnapRepair");
        assertThat(repair.getCompileTasks().stream().flatMap(source -> source.tasks().stream()).map(task -> task.getPath()))
                .containsExactlyInAnyOrder(":core:compileJava", ":app:compileJava");
        assertThat(repair.getTaskDependencies().getDependencies(repair)).extracting(task -> task.getPath())
                .contains(":core:compileJava", ":app:compileJava");
    }
}
