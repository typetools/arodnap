package org.arodnap.cli.capture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.arodnap.engine.inputs.ProjectCapture;
import org.arodnap.engine.inputs.UnsupportedProjectException;
import org.arodnap.engine.jdk.JdkLocator;
import org.arodnap.engine.process.CommandRunner;
import org.arodnap.model.BuildDescription;

/** The build recordings, and how the CLI picks one. */
public final class BuildRecordings {
    private BuildRecordings() {}

    /**
     * The recording for a project: with a build command, the one for that command's build tool (or
     * the plain command recording); otherwise the first build tool whose build file exists
     * (Gradle, then Maven, then Ant). Detection happens when the capture runs.
     */
    public static ProjectCapture select(BuildRecording.Hooks hooks, BuildRecording.Options options, CommandRunner runner, JdkLocator jdks) {
        List<BuildRecording> recordings = List.of(new Gradle(hooks, options, runner, jdks), new Maven(hooks, options, runner, jdks),
                new Ant(hooks, options, runner, jdks));
        if (!options.buildCommand().isEmpty()) {
            String executable = Path.of(options.buildCommand().get(0)).getFileName().toString();
            for (BuildRecording recording : recordings) {
                if (recording.executables().contains(executable)) {
                    return recording;
                }
            }
            return new Plain(hooks, options, runner, jdks);
        }
        return new ProjectCapture() {
            @Override
            public BuildDescription detect(Path projectRoot) throws UnsupportedProjectException {
                return pick(projectRoot).detect(projectRoot);
            }

            @Override
            public CapturedProject capture(Path workspaceRoot, Path stateDirectory) throws UnsupportedProjectException {
                return pick(workspaceRoot).capture(workspaceRoot, stateDirectory);
            }

            private BuildRecording pick(Path root) throws UnsupportedProjectException {
                for (BuildRecording recording : recordings) {
                    try {
                        recording.detect(root);
                        return recording;
                    } catch (UnsupportedProjectException e) {
                        // try the next build tool
                    }
                }
                throw new UnsupportedProjectException("No Gradle, Maven or Ant build file found in " + root + ". For other builds, pass the "
                        + "build command after --, e.g. `arodnap repair <repo> -- ./build.sh`.");
            }
        };
    }

    static final class Gradle extends BuildRecording {
        private static final List<String> FLAGS = List.of("--no-daemon", "--console=plain", "--no-build-cache", "--no-configuration-cache");
        private static final String INIT_SCRIPT = """
                // Arodnap: record the inputs of every JavaCompile task that runs, as javac arguments.
                import groovy.json.JsonOutput
                allprojects {
                    tasks.withType(JavaCompile).configureEach { task ->
                        task.doFirst {
                            def args = ["-d", task.destinationDirectory.get().asFile.absolutePath,
                                        "-classpath", task.classpath.asPath]
                            if (task.options.release.isPresent()) {
                                args += ["--release", task.options.release.get().toString()]
                            } else {
                                args += ["-source", task.sourceCompatibility, "-target", task.targetCompatibility]
                            }
                            def generated = task.options.generatedSourceOutputDirectory.getOrNull()
                            if (generated != null) {
                                args += ["-s", generated.asFile.absolutePath]
                            }
                            def processorPath = task.options.annotationProcessorPath
                            if (processorPath != null && !processorPath.isEmpty()) {
                                args += ["-processorpath", processorPath.asPath]
                            }
                            if (task.options.encoding != null) {
                                args += ["-encoding", task.options.encoding]
                            }
                            args += task.options.allCompilerArgs
                            args += task.source.files.collect { it.absolutePath }.sort()
                            new File(System.getenv("ARODNAP_CAPTURE_FILE")).append(
                                JsonOutput.toJson([cwd: task.project.projectDir.absolutePath, task: task.path, args: args]) + "\\n")
                        }
                    }
                }
                """;

        Gradle(Hooks hooks, Options options, CommandRunner runner, JdkLocator jdks) {
            super(hooks, options, runner, jdks);
        }

        @Override
        public String buildSystem() {
            return "gradle";
        }

        @Override
        List<String> executables() {
            return List.of("gradle", "gradlew");
        }

        @Override
        protected List<String> buildFiles() {
            return List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");
        }

        @Override
        protected List<String> command(Path projectRoot, Path captureDirectory) throws UnsupportedProjectException {
            Path initScript = captureDirectory.resolve("arodnap-capture.init.gradle");
            try {
                Files.writeString(initScript, INIT_SCRIPT, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UnsupportedProjectException("Could not write the Gradle init script: " + e.getMessage(), e);
            }
            List<String> hook = new ArrayList<>(FLAGS);
            hook.addAll(List.of("-I", initScript.toString()));
            List<String> buildCommand = options.buildCommand();
            if (!buildCommand.isEmpty()) {
                return concat(concat(List.of(buildCommand.get(0)), hook), buildCommand.subList(1, buildCommand.size()));
            }
            List<String> command = new ArrayList<>(List.of(tool(projectRoot, "gradlew", "gradle")));
            command.addAll(hook);
            command.addAll(options.buildArgs());
            command.add("clean");
            command.add(options.compileTarget().orElse("compileJava"));
            return command;
        }
    }

    static final class Maven extends BuildRecording {
        Maven(Hooks hooks, Options options, CommandRunner runner, JdkLocator jdks) {
            super(hooks, options, runner, jdks);
        }

        @Override
        public String buildSystem() {
            return "maven";
        }

        @Override
        List<String> executables() {
            return List.of("mvn", "mvnw");
        }

        @Override
        protected List<String> buildFiles() {
            return List.of("pom.xml");
        }

        @Override
        protected List<String> command(Path projectRoot, Path captureDirectory) throws UnsupportedProjectException {
            // A fork/executable override is ignored when the POM configures <fork> itself (the Apache
            // parent POM does), so record through a core extension instead.
            if (!Files.isRegularFile(hooks.mavenCaptureJar())) {
                throw new UnsupportedProjectException("Arodnap's Maven capture jar is missing: " + hooks.mavenCaptureJar());
            }
            String hook = "-Dmaven.ext.class.path=" + hooks.mavenCaptureJar();
            List<String> buildCommand = options.buildCommand();
            if (!buildCommand.isEmpty()) {
                return concat(buildCommand, List.of(hook));
            }
            List<String> command = new ArrayList<>(List.of(tool(projectRoot, "mvnw", "mvn"), "-B"));
            command.addAll(options.buildArgs());
            command.add("clean");
            command.add(options.compileTarget().orElse("compile"));
            command.add(hook);
            return command;
        }
    }

    static final class Ant extends BuildRecording {
        private static final String ADAPTER = "org.arodnap.capture.RecordingJavacAdapter";

        Ant(Hooks hooks, Options options, CommandRunner runner, JdkLocator jdks) {
            super(hooks, options, runner, jdks);
        }

        @Override
        public String buildSystem() {
            return "ant";
        }

        @Override
        List<String> executables() {
            return List.of("ant");
        }

        @Override
        protected List<String> buildFiles() {
            return List.of("build.xml");
        }

        @Override
        protected boolean touchSourcesFirst() {
            return true;
        }

        @Override
        protected List<String> command(Path projectRoot, Path captureDirectory) throws UnsupportedProjectException {
            if (!Files.isRegularFile(hooks.antCaptureJar())) {
                throw new UnsupportedProjectException("Arodnap's Ant capture jar is missing: " + hooks.antCaptureJar());
            }
            List<String> hook = List.of("-lib", hooks.antCaptureJar().toString(), "-Dbuild.compiler=" + ADAPTER);
            List<String> buildCommand = options.buildCommand();
            if (!buildCommand.isEmpty()) {
                return concat(concat(List.of(buildCommand.get(0)), hook), buildCommand.subList(1, buildCommand.size()));
            }
            if (jdks.findOnPath("ant").isEmpty()) {
                throw new UnsupportedProjectException("ant not found. Install Apache Ant.");
            }
            List<String> command = new ArrayList<>(List.of("ant"));
            command.addAll(hook);
            command.addAll(options.buildArgs());
            options.compileTarget().ifPresent(command::add);
            return command;
        }
    }

    /** Any build command that runs the javac executable (shell scripts, Make, ...). */
    static final class Plain extends BuildRecording {
        Plain(Hooks hooks, Options options, CommandRunner runner, JdkLocator jdks) {
            super(hooks, options, runner, jdks);
        }

        @Override
        public String buildSystem() {
            return "command";
        }

        @Override
        List<String> executables() {
            return List.of();
        }

        @Override
        protected List<String> buildFiles() {
            return List.of();
        }

        @Override
        protected Path buildFile(Path projectRoot) throws UnsupportedProjectException {
            if (options.buildCommand().isEmpty()) {
                throw new UnsupportedProjectException("A build command is required: arodnap <command> <repo> -- <build command>");
            }
            return projectRoot;
        }

        @Override
        protected boolean touchSourcesFirst() {
            return true;
        }

        @Override
        protected String buildToolSource(String executable) {
            return "command";
        }

        @Override
        protected List<String> command(Path projectRoot, Path captureDirectory) {
            return options.buildCommand();
        }
    }
}
