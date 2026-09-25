package org.arodnap.engine.analysis;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.arodnap.engine.jdk.Jdk;
import org.arodnap.engine.json.Json;
import org.arodnap.engine.pipeline.OutputLayout;
import org.arodnap.engine.pipeline.RunContext;
import org.arodnap.engine.pipeline.Workspace;
import org.arodnap.engine.process.Command;
import org.arodnap.engine.process.CommandException;
import org.arodnap.engine.process.CommandResult;
import org.arodnap.engine.tools.CheckerFramework;
import org.arodnap.model.BuildDescription;
import org.arodnap.model.CompileUnit;
import org.arodnap.model.ProjectInputs;

/**
 * Analyzes the workspace: compiles the program into the analysis classes, runs whole-program
 * inference to a fixpoint, then the Resource Leak Checker with the inferred annotations. Every
 * analysis recompiles the current sources with the inputs captured once for the run, so the
 * project's build never runs again.
 */
public final class Analyzer {
    // Same flags as the paper's final Resource Leak Checker pass, minus JVM heap and assertion settings.
    static final List<String> RLC_FLAGS = List.of("-Adetailedmsgtext", "-Awarns", "-Xmaxwarns", "10000", "-ApermitStaticOwning",
            "-AshowPrefixInWarningMessages", "-AenableReturnsReceiverForRlc");
    private static final Pattern WARNING = Pattern.compile("(?m)^.*: warning:");

    private final RunContext context;

    public Analyzer(RunContext context) {
        this.context = context;
    }

    /** The analysis classes directory: the program compiled from the current sources. */
    public Path analysisClasses() {
        return context.workspace().stateDirectory().resolve("analysis-classes");
    }

    /**
     * Analyzes the current workspace.
     *
     * @param inference run whole-program inference first ({@code infer} and {@code repair}); without
     *     it ({@code analyze}) the checker runs on the program as written
     */
    public Analysis analyze(String label, boolean inference) throws AnalysisException {
        OutputLayout.AnalysisFiles files = context.layout().analysis(label);
        try {
            files.create();
            ProjectInputs inputs = context.inputs();
            writeLines(files.sourceFiles(), allSources(inputs).stream().map(Path::toString).toList());
            writeLines(files.appClasses(), compileProgram());
            List<String> classpath = new ArrayList<>();
            classpath.add(analysisClasses().toString());
            inputs.classpath().forEach(entry -> classpath.add(entry.toString()));
            writeLines(files.classpathEntries(), classpath);
            writeAdapterMetadata(files);

            WholeProgramInference.Result wpi;
            if (inference) {
                wpi = new WholeProgramInference(context).run(files);
            } else {
                Files.writeString(files.wpiLog(), "SKIPPED: analyze does not run WPI.\n", StandardCharsets.UTF_8);
                Workspace.deleteTree(files.inferenceDirectory());
                Files.createDirectories(files.inferenceDirectory());
                wpi = null;
            }
            int warnings = runResourceLeakChecker(files, wpi == null ? Optional.empty() : Optional.of(files.inferenceDirectory()));
            List<String> notes = new ArrayList<>();
            if (wpi != null) {
                for (String ajava : wpi.incomplete()) {
                    notes.add("Whole-program inference could not cover " + Path.of(ajava).getFileName().toString().split("-")[0]
                            + ": the Checker Framework failed to write " + ajava + " (see " + files.wpiLog() + ").");
                }
            }
            return new Analysis(label, context.workspace().root(), files.wpiLog(), files.inferenceDirectory(), files.diagnostics(),
                    warnings, files.sourceFiles(), files.appClasses(), files.classpathEntries(), files.adapterMetadata(),
                    inputs.release(), inputs.encoding(), notes);
        } catch (IOException e) {
            throw new AnalysisException("Could not write the analysis files for " + label + ": " + e.getMessage(), e);
        }
    }

    static List<Path> allSources(ProjectInputs inputs) {
        List<Path> sources = new ArrayList<>(inputs.sources());
        sources.addAll(inputs.generatedSources());
        return sources;
    }

    /** Compiles the sources together into the analysis classes; returns the program's class names, sorted. */
    private List<String> compileProgram() throws AnalysisException, IOException {
        ProjectInputs inputs = context.inputs();
        Path classes = analysisClasses();
        Workspace.deleteTree(classes);
        Files.createDirectories(classes);
        Path sourcesFile = classes.getParent().resolve("analysis-sources.txt");
        StringBuilder quoted = new StringBuilder();
        for (Path source : allSources(inputs)) {
            quoted.append(("\"" + source + "\"\n").replace("\\", "\\\\"));
        }
        Files.writeString(sourcesFile, quoted, StandardCharsets.UTF_8);
        List<String> command = new ArrayList<>(List.of(context.jdk().javac().toString(), "-d", classes.toString(), "-proc:none", "-nowarn",
                "-Xlint:none"));
        if (!inputs.classpath().isEmpty()) {
            command.add("-classpath");
            command.add(joinPaths(inputs.classpath()));
        }
        command.addAll(Jdk.languageOptions(inputs.release(), inputs.encoding()));
        command.add("@" + sourcesFile);
        CommandResult result;
        try {
            result = context.runAnalysisCommand(Command.of(command, context.workspace().root()));
        } catch (CommandException e) {
            throw new AnalysisException(e.getMessage(), e);
        }
        if (!result.succeeded()) {
            throw new AnalysisException("Arodnap could not compile the captured sources together as one program:\n"
                    + tail(result.stdout() + result.stderr(), 40));
        }
        try (Stream<Path> files = Files.walk(classes)) {
            return files.filter(file -> file.toString().endsWith(".class") && !file.getFileName().toString().equals("module-info.class"))
                    .map(file -> {
                        String relative = classes.relativize(file).toString();
                        return relative.substring(0, relative.length() - ".class".length()).replace(File.separatorChar, '.');
                    })
                    .sorted()
                    .toList();
        }
    }

    private int runResourceLeakChecker(OutputLayout.AnalysisFiles files, Optional<Path> inferred) throws AnalysisException, IOException {
        Path classes = Files.createTempDirectory("arodnap-rlc-classes-");
        try {
            List<String> command = new ArrayList<>(CheckerFramework.javacCommand(context.toolchain(), context.jdk()));
            command.addAll(List.of("-processor", CheckerFramework.RESOURCE_LEAK_CHECKER));
            command.addAll(RLC_FLAGS);
            command.add("-Astubs=" + context.toolchain().stubsDirectory());
            inferred.ifPresent(directory -> command.add("-Aajava=" + directory));
            command.addAll(Jdk.languageOptions(context.inputs().release(), context.inputs().encoding()));
            command.addAll(List.of("-classpath", joinPaths(classpathEntries(files)), "-d", classes.toString(), "@" + files.sourceFiles()));
            CommandResult result;
            try {
                result = context.runAnalysisCommand(Command.of(command, context.workspace().root()));
            } catch (CommandException.TimedOut e) {
                throw new AnalysisException("The Resource Leak Checker exceeded --analysis-timeout. " + e.getMessage(), e);
            } catch (CommandException e) {
                throw new AnalysisException(e.getMessage(), e);
            }
            String diagnostics = result.log(Optional.of("rlc"));
            Files.writeString(files.diagnostics(), diagnostics, StandardCharsets.UTF_8);
            if (!result.succeeded()) {
                throw new AnalysisException("RLC failed for " + context.workspace().root() + ". See diagnostics: " + files.diagnostics());
            }
            return countWarnings(diagnostics);
        } finally {
            Workspace.deleteTree(classes);
        }
    }

    static List<Path> classpathEntries(OutputLayout.AnalysisFiles files) throws IOException {
        return Files.readAllLines(files.classpathEntries(), StandardCharsets.UTF_8).stream()
                .map(String::strip).filter(line -> !line.isEmpty()).map(Path::of).toList();
    }

    public static int countWarnings(String diagnostics) {
        Matcher matcher = WARNING.matcher(diagnostics);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private void writeAdapterMetadata(OutputLayout.AnalysisFiles files) throws IOException {
        ProjectInputs inputs = context.inputs();
        BuildDescription build = context.captured().build();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("repo_root", context.workspace().root().toString());
        metadata.put("build_file", build.buildFile().toString());
        metadata.put("build_system", build.buildSystem());
        metadata.put("adapter_name", build.adapterName());
        metadata.put("build_tool", build.buildTool());
        metadata.put("build_tool_source", build.buildToolSource());
        metadata.put("compile_target", build.compileTarget());
        metadata.put("source_root", inputs.sourceRoot().toString());
        metadata.put("compiled_classes_root", analysisClasses().toString());
        metadata.put("source_files_file", files.sourceFiles().toString());
        metadata.put("app_classes_file", files.appClasses().toString());
        metadata.put("classpath_entries_file", files.classpathEntries().toString());
        metadata.put("build_command", build.buildCommand());
        metadata.put("release", inputs.release().orElse(null));
        metadata.put("encoding", inputs.encoding().orElse(null));
        metadata.put("generated_source_count", inputs.generatedSources().size());
        List<Map<String, Object>> units = new ArrayList<>();
        for (CompileUnit unit : inputs.units()) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("label", unit.label().orElse(null));
            json.put("cwd", unit.workingDirectory().toString());
            json.put("source_count", unit.sources().size());
            json.put("output_dir", unit.outputDirectory().map(Path::toString).orElse(null));
            json.put("release", unit.release().orElse(null));
            units.add(json);
        }
        metadata.put("compile_units", units);
        Json.writeFile(files.adapterMetadata(), metadata, true);
        // Keep what the build did next to the metadata for debugging.
        for (Path log : context.captured().logs()) {
            if (Files.isRegularFile(log)) {
                Files.copy(log, files.logsDirectory().resolve(log.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    static String joinPaths(List<Path> paths) {
        return String.join(File.pathSeparator, paths.stream().map(Path::toString).toList());
    }

    static void writeLines(Path file, List<String> lines) throws IOException {
        StringBuilder text = new StringBuilder();
        lines.forEach(line -> text.append(line).append('\n'));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    static String tail(String text, int lines) {
        List<String> all = text.strip().lines().toList();
        return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
    }
}
