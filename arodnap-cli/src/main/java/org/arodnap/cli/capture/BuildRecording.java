package org.arodnap.cli.capture;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.arodnap.engine.inputs.CompileUnits;
import org.arodnap.engine.inputs.ProjectCapture;
import org.arodnap.engine.inputs.UnsupportedProjectException;
import org.arodnap.engine.jdk.JdkLocator;
import org.arodnap.engine.pipeline.Timeouts;
import org.arodnap.engine.pipeline.Workspace;
import org.arodnap.engine.process.Command;
import org.arodnap.engine.process.CommandException;
import org.arodnap.engine.process.CommandResult;
import org.arodnap.engine.process.CommandRunner;
import org.arodnap.model.BuildDescription;
import org.arodnap.model.ProjectInputs;

/**
 * Learns a project's compilation by watching its own build. The build runs once in the workspace
 * copy with a recording hook installed through the build tool's official extension point:
 *
 * <ul>
 *   <li>Gradle: an init script records the inputs of every JavaCompile task.
 *   <li>Maven: a core extension records every {@code maven-compiler-plugin:compile} execution.
 *   <li>Ant: a recording compiler adapter.
 *   <li>Any other command: a recording {@code javac} comes first on PATH.
 * </ul>
 *
 * The recording javac is on PATH for Gradle, Maven and Ant too, so scripts they call that run javac
 * directly are recorded as well.
 */
public abstract class BuildRecording implements ProjectCapture {
    /** What the recordings need besides the build: the hook jars and a way to start the recorder. */
    public record Hooks(Path antCaptureJar, Path mavenCaptureJar, Path recorderClasspath, Path java) {}

    /** The user's build options. */
    public record Options(List<String> buildArgs, Optional<String> compileTarget, List<String> buildCommand, Timeouts timeouts) {
        public Options {
            buildArgs = List.copyOf(buildArgs);
            buildCommand = List.copyOf(buildCommand);
        }
    }

    protected final Hooks hooks;
    protected final Options options;
    protected final CommandRunner runner;
    protected final JdkLocator jdks;

    protected BuildRecording(Hooks hooks, Options options, CommandRunner runner, JdkLocator jdks) {
        this.hooks = hooks;
        this.options = options;
        this.runner = runner;
        this.jdks = jdks;
    }

    /** {@code gradle}, {@code maven}, {@code ant} or {@code command}. */
    public abstract String buildSystem();

    /** Executable names (from {@code -- <command>}) this recording knows how to hook into. */
    abstract List<String> executables();

    /** Build files that identify the build, in order of preference. */
    protected abstract List<String> buildFiles();

    /** The command that runs the build with the recording hook. */
    protected abstract List<String> command(Path projectRoot, Path captureDirectory) throws UnsupportedProjectException;

    /** Ant and plain commands only recompile sources newer than their classes; the copy keeps timestamps. */
    protected boolean touchSourcesFirst() {
        return false;
    }

    protected String buildToolSource(String executable) {
        return executable.startsWith("./") ? "wrapper" : "system";
    }

    protected Path buildFile(Path projectRoot) throws UnsupportedProjectException {
        for (String name : buildFiles()) {
            Path candidate = projectRoot.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new UnsupportedProjectException(buildSystem() + " build file (" + String.join(" or ", buildFiles()) + ") not found in "
                + projectRoot);
    }

    @Override
    public BuildDescription detect(Path projectRoot) throws UnsupportedProjectException {
        return new BuildDescription(buildSystem(), buildSystem(), buildFile(projectRoot), List.of(), "", options.compileTarget().orElse(""),
                List.of());
    }

    @Override
    public CapturedProject capture(Path workspaceRoot, Path stateDirectory) throws UnsupportedProjectException {
        Path buildFile = buildFile(workspaceRoot);
        Path captureDirectory = stateDirectory.resolve("capture");
        Path captureFile = captureDirectory.resolve("javac-invocations.jsonl");
        Path buildLog = captureDirectory.resolve("build.log");
        CommandResult result;
        List<String> command;
        try {
            Workspace.deleteTree(captureDirectory);
            Files.createDirectories(captureDirectory);
            Path shimDirectory = writeJavacShim(captureDirectory);
            command = command(workspaceRoot, captureDirectory);
            if (touchSourcesFirst()) {
                touchJavaSources(workspaceRoot);
            }
            Map<String, String> environment = new HashMap<>();
            environment.put("ARODNAP_CAPTURE_FILE", captureFile.toString());
            environment.put("ARODNAP_REAL_JAVAC", realJavac());
            environment.put("PATH", shimDirectory + File.pathSeparator + System.getenv().getOrDefault("PATH", ""));
            try {
                result = runner.run(new Command(command, workspaceRoot, environment, options.timeouts().build()));
            } catch (CommandException.TimedOut e) {
                throw new UnsupportedProjectException("The build did not finish within " + e.limit().toSeconds()
                        + " seconds (--build-timeout): " + String.join(" ", command), e);
            } catch (CommandException e) {
                throw new UnsupportedProjectException("Could not run the build command '" + command.get(0) + "': " + e.getMessage(), e);
            }
            Files.writeString(buildLog, result.log(Optional.of(buildSystem() + " build")), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UnsupportedProjectException("Could not prepare the build recording: " + e.getMessage(), e);
        }
        if (!result.succeeded()) {
            throw new UnsupportedProjectException("The build failed (exit code " + result.exitCode() + "): " + String.join(" ", command) + "\n"
                    + tail(result.stdout() + result.stderr(), 40));
        }
        ProjectInputs inputs;
        try {
            inputs = CompileUnits.merge(CompileUnits.load(captureFile));
        } catch (IOException e) {
            throw new UnsupportedProjectException("Could not read the recorded javac calls in " + captureFile + ": " + e.getMessage(), e);
        }
        // Ant puts its own runtime, including Arodnap's capture adapter, on javac's class path.
        Path hookDirectory = hooks.antCaptureJar().toAbsolutePath().getParent();
        List<Path> classpath = inputs.classpath().stream().filter(entry -> !hookDirectory.equals(entry.getParent())).toList();
        inputs = new ProjectInputs(inputs.units(), inputs.sources(), inputs.generatedSources(), classpath, inputs.release(), inputs.encoding(),
                inputs.sourceRoot());
        BuildDescription build = new BuildDescription(buildSystem(), buildSystem(), buildFile, List.of(command.get(0)),
                buildToolSource(command.get(0)), options.compileTarget().orElse(""), command);
        return new CapturedProject(inputs, build, List.of(captureFile, buildLog));
    }

    private Path writeJavacShim(Path captureDirectory) throws IOException {
        Path directory = Files.createDirectories(captureDirectory.resolve("bin"));
        Path shim = directory.resolve("javac");
        Files.writeString(shim, "#!/bin/sh\n# Arodnap's recording javac: logs the invocation, then runs the real javac.\nexec " + shellQuote(hooks.java().toString())
                + " -cp " + shellQuote(hooks.recorderClasspath().toString()) + " " + JavacRecorder.class.getName() + " \"$@\"\n",
                StandardCharsets.UTF_8);
        shim.toFile().setExecutable(true);
        return directory;
    }

    private String realJavac() {
        try {
            return jdks.locate().javac().toString();
        } catch (JdkLocator.JdkNotFoundException e) {
            return jdks.findOnPath("javac").map(Path::toString).orElse("javac");
        }
    }

    /** {@code ./wrapper} when the project has an executable wrapper, else the build tool on PATH. */
    protected String tool(Path projectRoot, String wrapper, String system) throws UnsupportedProjectException {
        Path wrapperPath = projectRoot.resolve(wrapper);
        if (Files.isRegularFile(wrapperPath) && Files.isExecutable(wrapperPath)) {
            return "./" + wrapper;
        }
        if (jdks.findOnPath(system).isPresent()) {
            return system;
        }
        throw new UnsupportedProjectException(system + " not found. Install it or add ./" + wrapper + " to the project.");
    }

    private static void touchJavaSources(Path root) throws IOException {
        FileTime now = FileTime.fromMillis(System.currentTimeMillis());
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java") && Files.isRegularFile(path)).toList()) {
                Files.setLastModifiedTime(file, now);
            }
        }
    }

    static String tail(String text, int lines) {
        List<String> all = text.strip().lines().toList();
        return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
    }

    static String shellQuote(String word) {
        return "'" + word.replace("'", "'\"'\"'") + "'";
    }

    protected static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }
}
