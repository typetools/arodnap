package org.arodnap.gradle;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.arodnap.engine.doctor.Doctor;
import org.arodnap.engine.inputs.CompileUnits;
import org.arodnap.engine.inputs.ProjectCapture;
import org.arodnap.engine.inputs.Relocation;
import org.arodnap.engine.inputs.UnsupportedProjectException;
import org.arodnap.engine.jdk.JdkLocator;
import org.arodnap.engine.pipeline.Engine;
import org.arodnap.engine.pipeline.RunFailedException;
import org.arodnap.engine.pipeline.RunSettings;
import org.arodnap.engine.pipeline.RunSettings.FieldTransformationMode;
import org.arodnap.engine.pipeline.Timeouts;
import org.arodnap.engine.process.CommandRunner;
import org.arodnap.engine.tools.Stubs;
import org.arodnap.engine.tools.ToolCoordinates;
import org.arodnap.engine.tools.Toolchain;
import org.arodnap.model.BuildDescription;
import org.arodnap.model.CompileUnit;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.compile.JavaCompile;

/** Runs one Arodnap command on the whole build: every project's main Java compilation. */
public abstract class RunTask extends DefaultTask {
    private final List<CompileSource> compileTasks = new ArrayList<>();
    private final Map<String, FileCollection> tools = new LinkedHashMap<>();

    @Input
    public abstract Property<String> getCommand();

    @Internal
    public abstract DirectoryProperty getOutputDirectory();

    @Internal
    public abstract DirectoryProperty getProjectDirectory();

    @Input
    public abstract Property<String> getFieldTransformations();

    @Input
    public abstract Property<Boolean> getKeepWorkspace();

    @Input
    @Optional
    public abstract Property<Integer> getAnalysisTimeout();

    @Input
    @Optional
    public abstract Property<Integer> getStageTimeout();

    /** A project's main compile task and the project's directory, collected when the build is configured. */
    public record CompileSource(JavaCompile task, File projectDirectory) {}

    /** The build's main compile tasks. */
    @Internal
    public List<CompileSource> getCompileTasks() {
        return compileTasks;
    }

    /** Each tool's artifact, by the names in the engine's tools.properties. */
    @Internal
    public Map<String, FileCollection> getTools() {
        return tools;
    }

    @TaskAction
    public void run() {
        Path root = getProjectDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
        Path output = getOutputDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
        String command = getCommand().get();
        String fields = getFieldTransformations().get();
        if (!List.of("resources", "all", "off").contains(fields)) {
            throw new GradleException("arodnap.fieldTransformations: expected resources, all or off, got " + fields);
        }
        RunSettings settings = new RunSettings(command, root, output, getKeepWorkspace().get(),
                new Timeouts(java.util.Optional.empty(), seconds(getAnalysisTimeout().getOrNull()), seconds(getStageTimeout().getOrNull())),
                FieldTransformationMode.fromCliName(fields), List.of(), java.util.Optional.of("compileJava"));
        Toolchain toolchain = toolchain(output.resolve("tools"));
        CommandRunner runner = CommandRunner.processes();
        JdkLocator jdks = JdkLocator.fromEnvironment(runner);
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        ProjectCapture capture = new BuildCapture(root, compileTasks);
        try {
            switch (command) {
                case "repair" -> new Engine(toolchain, runner, jdks, out, (patches, repo) -> "./gradlew arodnapApply").repair(settings, capture);
                case "analyze" -> new Engine(toolchain, runner, jdks, out, (patches, repo) -> "").analyze(settings, capture);
                case "infer" -> new Engine(toolchain, runner, jdks, out, (patches, repo) -> "").infer(settings, capture);
                case "doctor" -> {
                    if (!new Doctor(toolchain, jdks).run(settings, capture, out)) {
                        throw new GradleException("Arodnap's checks failed; see " + output.resolve("doctor.json"));
                    }
                }
                default -> throw new GradleException("Unknown Arodnap command: " + command);
            }
        } catch (RunFailedException e) {
            throw new GradleException("Arodnap failed: " + e.getMessage() + " (details in " + output.resolve("report.json") + ")", e);
        } catch (IOException e) {
            throw new GradleException("Arodnap failed: " + e.getMessage(), e);
        }
    }

    private Toolchain toolchain(Path directory) {
        try {
            Path checker = Files.createDirectories(directory.resolve("checker-framework"));
            for (String name : List.of("checker", "checker-qual", "checker-util")) {
                Files.copy(tool(name), checker.resolve(name + ".jar"), StandardCopyOption.REPLACE_EXISTING);
            }
            return new Toolchain(checker.resolve("checker.jar"), Stubs.extract(directory.resolve("stubs")), tool("close-injector"),
                    tool("owning-field-fixer"), tool("rlfixer"), tool("rlpatcher"), tool("field-transformations"), tool("error-prone"),
                    tool("error-prone-jdk17"), tool("dataflow"), ToolCoordinates.checkerFrameworkVersion(), ToolCoordinates.CHECKER_FRAMEWORK_TESTED_JDKS);
        } catch (IOException e) {
            throw new GradleException("Cannot set up Arodnap's tools in " + directory + ": " + e.getMessage(), e);
        }
    }

    private Path tool(String name) {
        FileCollection files = tools.get(name);
        if (files == null) {
            throw new GradleException("Arodnap's " + name + " is not configured.");
        }
        List<File> resolved = new ArrayList<>(files.getFiles());
        if (resolved.size() != 1) {
            throw new GradleException("Arodnap's " + name + " (" + ToolCoordinates.of(name) + ") resolved to " + resolved + ".");
        }
        return resolved.get(0).toPath();
    }

    private static java.util.Optional<Duration> seconds(Integer value) {
        if (value == null) {
            return java.util.Optional.empty();
        }
        if (value <= 0) {
            throw new GradleException("Arodnap time limits must be a positive number of seconds, got " + value);
        }
        return java.util.Optional.of(Duration.ofSeconds(value));
    }

    /** The inputs of every project, read from its main JavaCompile task after it ran. */
    static final class BuildCapture implements ProjectCapture {
        private final Path root;
        private final List<CompileSource> compileTasks;

        BuildCapture(Path root, List<CompileSource> compileTasks) {
            this.root = root;
            this.compileTasks = compileTasks;
        }

        @Override
        public BuildDescription detect(Path projectRoot) {
            Path buildFile = Files.exists(projectRoot.resolve("build.gradle.kts")) ? projectRoot.resolve("build.gradle.kts")
                    : projectRoot.resolve("build.gradle");
            return new BuildDescription("gradle", "gradle-plugin", buildFile, List.of("gradle"), "plugin", "compileJava", List.of());
        }

        @Override
        public CapturedProject capture(Path workspaceRoot, Path stateDirectory) throws UnsupportedProjectException {
            List<CompileUnit> units = new ArrayList<>();
            for (CompileSource source : compileTasks) {
                JavaCompile compile = source.task();
                List<Path> sources = compile.getSource().getFiles().stream().map(File::toPath).filter(path -> path.toString().endsWith(".java"))
                        .sorted().toList();
                if (sources.isEmpty()) {
                    continue;
                }
                java.util.Optional<Integer> release = java.util.Optional.ofNullable(compile.getOptions().getRelease().getOrNull())
                        .or(() -> level(compile.getSourceCompatibility()));
                java.util.Optional<Path> generated = java.util.Optional.ofNullable(compile.getOptions().getGeneratedSourceOutputDirectory().getOrNull())
                        .map(directory -> directory.getAsFile().toPath());
                FileCollection processorPath = compile.getOptions().getAnnotationProcessorPath();
                units.add(new CompileUnit(source.projectDirectory().toPath(), sources,
                        compile.getClasspath().getFiles().stream().map(File::toPath).toList(),
                        java.util.Optional.of(compile.getDestinationDirectory().get().getAsFile().toPath()), generated, release,
                        java.util.Optional.ofNullable(compile.getOptions().getEncoding()),
                        processorPath == null ? List.of() : processorPath.getFiles().stream().map(File::toPath).toList(), List.of(),
                        java.util.Optional.of(compile.getPath())));
            }
            return new CapturedProject(CompileUnits.merge(new Relocation(root, workspaceRoot).relocateUnits(units)), detect(workspaceRoot), List.of());
        }

        private static java.util.Optional<Integer> level(String value) {
            if (value == null) {
                return java.util.Optional.empty();
            }
            String level = value.startsWith("1.") ? value.substring(2) : value;
            return level.chars().allMatch(Character::isDigit) && !level.isEmpty() ? java.util.Optional.of(Integer.parseInt(level))
                    : java.util.Optional.empty();
        }
    }
}
