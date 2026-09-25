package org.arodnap.gradle;

import java.util.List;
import org.arodnap.engine.tools.ToolCoordinates;
import org.gradle.api.Plugin;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;

/**
 * Arodnap for Gradle. Apply it to the root project; its tasks cover every project of the build:
 *
 * <ul>
 *   <li>{@code arodnapRepair}: repairs resource leaks on a copy of the build and writes one verified patch
 *   <li>{@code arodnapApply}: applies that patch to the project
 *   <li>{@code arodnapAnalyze}, {@code arodnapInfer}: the Resource Leak Checker without and with inference
 *   <li>{@code arodnapDoctor}: checks that Arodnap can run on the build
 * </ul>
 */
public class ArodnapPlugin implements Plugin<Project> {
    public static final String GROUP = "arodnap";
    private static final List<String> TOOLS = List.of("checker", "checker-qual", "checker-util", "close-injector", "owning-field-fixer", "rlfixer",
            "rlpatcher", "field-transformations", "error-prone", "error-prone-jdk17", "dataflow");

    @Override
    public void apply(Project project) {
        ArodnapExtension extension = project.getExtensions().create("arodnap", ArodnapExtension.class);
        extension.getOutputDirectory().convention(project.getLayout().getBuildDirectory().dir("arodnap"));
        extension.getFieldTransformations().convention("resources");
        extension.getKeepWorkspace().convention(false);

        register(project, "arodnapRepair", "repair", "Repairs resource leaks on a copy of the build and writes one verified patch.", extension);
        register(project, "arodnapAnalyze", "analyze", "Runs the Resource Leak Checker once, without inference.", extension);
        register(project, "arodnapInfer", "infer", "Runs whole-program inference, then the Resource Leak Checker.", extension);
        register(project, "arodnapDoctor", "doctor", "Checks that Arodnap can run on the build.", extension);
        project.getTasks().register("arodnapApply", ApplyTask.class, task -> {
            task.setGroup(GROUP);
            task.setDescription("Applies the patch arodnapRepair wrote to the project.");
            task.getPatchDirectory().convention(extension.getOutputDirectory().dir("patches"));
            task.getOutputDirectory().convention(extension.getOutputDirectory());
            task.getProjectDirectory().set(project.getLayout().getProjectDirectory());
        });
    }

    private static void register(Project project, String name, String command, String description, ArodnapExtension extension) {
        TaskProvider<RunTask> task = project.getTasks().register(name, RunTask.class, run -> {
            run.setGroup(GROUP);
            run.setDescription(description);
            run.getCommand().set(command);
            run.getOutputDirectory().convention(extension.getOutputDirectory());
            run.getFieldTransformations().convention(extension.getFieldTransformations());
            run.getKeepWorkspace().convention(extension.getKeepWorkspace());
            run.getAnalysisTimeout().convention(extension.getAnalysisTimeout());
            run.getStageTimeout().convention(extension.getStageTimeout());
            run.getProjectDirectory().set(project.getLayout().getProjectDirectory());
            run.notCompatibleWithConfigurationCache("Arodnap reads the compile tasks of every project of the build.");
            run.getOutputs().upToDateWhen(ignored -> false);
        });
        task.configure(run -> {
            for (String tool : TOOLS) {
                Configuration configuration = project.getConfigurations().maybeCreate("arodnap_" + tool.replace('-', '_'));
                configuration.setCanBeConsumed(false);
                configuration.setCanBeResolved(true);
                configuration.setTransitive(false);
                if (configuration.getDependencies().isEmpty()) {
                    project.getDependencies().add(configuration.getName(), notation(ToolCoordinates.of(tool)));
                }
                run.getTools().put(tool, configuration);
            }
        });
        // The analysis needs every project compiled; it reads each project's main compile task.
        project.allprojects(each -> each.getTasks().withType(JavaCompile.class).matching(compile -> compile.getName().equals("compileJava"))
                .configureEach(compile -> task.configure(run -> {
                    run.dependsOn(compile);
                    run.getCompileTasks().add(new RunTask.CompileSource(compile, each.getProjectDir()));
                })));
    }

    /** Gradle's dependency notation (group:name:version:classifier) for group:artifact[:classifier]:version. */
    static String notation(String coordinates) {
        String[] parts = coordinates.split(":");
        return parts.length == 4 ? parts[0] + ":" + parts[1] + ":" + parts[3] + ":" + parts[2] : coordinates;
    }
}
