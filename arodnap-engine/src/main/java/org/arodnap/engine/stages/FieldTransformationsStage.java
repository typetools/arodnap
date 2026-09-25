package org.arodnap.engine.stages;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.arodnap.engine.files.FilePaths;
import org.arodnap.engine.jdk.Jdk;
import org.arodnap.engine.json.Json;
import org.arodnap.engine.patch.Lines;
import org.arodnap.engine.patch.UnifiedDiff;
import org.arodnap.engine.pipeline.RunContext;
import org.arodnap.engine.pipeline.RunSettings.FieldTransformationMode;
import org.arodnap.engine.pipeline.Workspace;
import org.arodnap.engine.process.Command;
import org.arodnap.engine.process.CommandException;
import org.arodnap.engine.process.CommandResult;
import org.arodnap.engine.tools.Toolchain;
import org.arodnap.model.ProjectInputs;
import org.arodnap.model.StageResult;

/**
 * Field transformations: private resource fields become final, or local variables when every method
 * assigns them before use (the paper's "preventing reassignment" and "reducing scope").
 *
 * <p>This stage runs before the initial analysis, so it costs no extra analysis. The two Error
 * Prone checks edit the workspace in place; each edit is already compile-checked for its own file.
 * After each check the whole program is compiled, and the edits of any file javac reports errors in
 * are undone until it compiles.
 */
public final class FieldTransformationsStage {
    public static final String NAME = "field_transformations";
    private static final List<String[]> CHECKS = List.of(new String[] {"ResourceFieldCanBeFinal", "final"},
            new String[] {"ResourceFieldCanBeLocal", "local"});
    /**
     * Types the Checker Framework's annotated JDK marks @MustCall without being AutoCloseable, with
     * their must-call methods.
     */
    public static final List<String> EXTRA_RESOURCE_TYPES = List.of("java.net.HttpURLConnection:disconnect");
    private static final int MAX_REPAIR_ROUNDS = 20;
    private static final List<String> JAVAC_EXPORTS;
    // Error Prone reports SUGGESTION-level findings as javac notes.
    private static final Pattern MATCH = Pattern.compile(
            "^(.+?\\.java):(\\d+): (?:warning|Note|note): \\[(ResourceFieldCan\\w+)\\] Resource field `([^`]+)`", Pattern.MULTILINE);
    private static final Pattern ERROR_FILE = Pattern.compile("^(.+?\\.java):\\d+: error:", Pattern.MULTILINE);
    private static final Pattern IDENTIFIER_LITERAL = Pattern.compile("\"([A-Za-z_$][A-Za-z0-9_$]*)\"");

    static {
        List<String> exports = new ArrayList<>();
        for (String name : List.of("api", "file", "main", "model", "parser", "processing", "tree", "util")) {
            exports.add("-J--add-exports=jdk.compiler/com.sun.tools.javac." + name + "=ALL-UNNAMED");
        }
        for (String name : List.of("code", "comp")) {
            exports.add("-J--add-opens=jdk.compiler/com.sun.tools.javac." + name + "=ALL-UNNAMED");
        }
        JAVAC_EXPORTS = List.copyOf(exports);
    }

    private record Change(String file, int line, String field, String change, boolean kept) {
        Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("file", file);
            json.put("line", line);
            json.put("field", field);
            json.put("change", change);
            json.put("kept", kept);
            return json;
        }
    }

    public StageResult run(RunContext context, Path analysisClasses) throws StageException {
        Path directory = context.layout().stage(NAME);
        Path log = directory.resolve("stage.log");
        Path workspaceRoot = context.workspace().root();
        FieldTransformationMode mode = context.settings().fieldTransformations();
        try {
            StageLog.reset(log);
            if (mode == FieldTransformationMode.OFF) {
                return finish(directory, List.of(), List.of("Field transformations are off (--field-transformations=off)."),
                        Map.of("log", log.toString()));
            }
            Jdk jdk = context.jdk();
            Toolchain toolchain = context.toolchain();
            Toolchain.ErrorProne errorProne = toolchain.errorProneFor(jdk);
            for (Path jar : List.of(toolchain.fieldTransformationsJar(), toolchain.dataflowJar(), errorProne.jar())) {
                if (!Files.isRegularFile(jar)) {
                    throw new StageException("Missing " + jar.getFileName() + " in " + jar.getParent());
                }
            }

            // The program's sources and class path, as every compile of this stage uses them.
            ProjectInputs inputs = context.inputs();
            Path inputsDirectory = Files.createDirectories(directory.resolve("inputs"));
            Path sourcesFile = inputsDirectory.resolve("sources.txt");
            List<Path> sources = new ArrayList<>(inputs.sources());
            sources.addAll(inputs.generatedSources());
            writeLines(sourcesFile, sources.stream().map(Path::toString).toList());
            List<String> classpathEntries = new ArrayList<>();
            classpathEntries.add(analysisClasses.toString());
            inputs.classpath().forEach(entry -> classpathEntries.add(entry.toString()));
            writeLines(inputsDirectory.resolve("classpath.txt"), classpathEntries);
            String classpath = String.join(File.pathSeparator, classpathEntries);
            List<String> language = Jdk.languageOptions(inputs.release(), inputs.encoding());

            Map<Path, byte[]> originals = new LinkedHashMap<>();
            for (Path source : sources) {
                if (Files.isRegularFile(source)) {
                    originals.put(FilePaths.real(source), Files.readAllBytes(source));
                }
            }
            Path reflected = directory.resolve("reflected-names.txt");
            Files.writeString(reflected, String.join("\n", stringLiteralNames(originals.values())) + "\n", StandardCharsets.UTF_8);

            List<Change> changes = new ArrayList<>();
            Set<Path> dropped = new HashSet<>();
            for (String[] check : CHECKS) {
                String name = check[0];
                Map<Path, byte[]> before = snapshot(originals.keySet());
                String options = String.join(" ", "-Xplugin:ErrorProne", "-XepDisableAllChecks", "-Xep:" + name, "-XepPatchChecks:" + name,
                        "-XepPatchLocation:IN_PLACE", "-XepOpt:ArodnapFields:ExtraResourceTypes=" + String.join(",", EXTRA_RESOURCE_TYPES),
                        "-XepOpt:ArodnapFields:ReflectedNamesFile=" + reflected,
                        "-XepOpt:ArodnapFields:AllFields=" + (mode == FieldTransformationMode.ALL ? "true" : "false"));
                Path classes = Files.createTempDirectory("arodnap-fields-");
                CommandResult result;
                try {
                    List<String> command = new ArrayList<>();
                    command.add(jdk.javac().toString());
                    command.addAll(JAVAC_EXPORTS);
                    command.addAll(List.of("-XDcompilePolicy=simple", "--should-stop=ifError=FLOW"));
                    command.addAll(errorProne.javacFlags());
                    command.addAll(List.of("-processorpath", String.join(File.pathSeparator, errorProne.jar().toString(),
                            toolchain.dataflowJar().toString(), toolchain.fieldTransformationsJar().toString())));
                    command.addAll(List.of(options, "-proc:none", "-d", classes.toString(), "-classpath", classpath));
                    command.addAll(language);
                    command.add("@" + sourcesFile.toAbsolutePath());
                    result = run(context, Command.of(command, workspaceRoot));
                } finally {
                    Workspace.deleteTree(classes);
                }
                StageLog.append(log, name, result, "error-prone");
                if (!result.succeeded()) {
                    throw new StageException("Error Prone failed while running " + name + " (see " + log + "). "
                            + "Use --field-transformations=off to skip field transformations.");
                }
                record Match(Path file, int line, String field) {}
                List<Match> matched = new ArrayList<>();
                Matcher matcher = MATCH.matcher(result.stdout() + result.stderr());
                while (matcher.find()) {
                    matched.add(new Match(FilePaths.real(Path.of(matcher.group(1))), Integer.parseInt(matcher.group(2)), matcher.group(4)));
                }
                Set<Path> edited = new HashSet<>();
                for (Map.Entry<Path, byte[]> entry : before.entrySet()) {
                    if (!Arrays.equals(Files.readAllBytes(entry.getKey()), entry.getValue())) {
                        edited.add(entry.getKey());
                    }
                }
                Set<Path> undone = compileUntilClean(context, classpath, language, sourcesFile, before, edited, log, name);
                dropped.addAll(undone);
                for (Match match : matched) {
                    if (before.containsKey(match.file())) {
                        changes.add(new Change(relative(match.file(), workspaceRoot), match.line(), match.field(), check[1],
                                !undone.contains(match.file())));
                    }
                }
            }

            List<Path> changed = new ArrayList<>();
            for (Map.Entry<Path, byte[]> entry : originals.entrySet()) {
                if (!Arrays.equals(Files.readAllBytes(entry.getKey()), entry.getValue())) {
                    changed.add(entry.getKey());
                }
            }
            changed.sort(FilePaths.BY_NAMES);
            StringBuilder patch = new StringBuilder();
            for (Path file : changed) {
                patch.append(UnifiedDiff.create(relative(file, workspaceRoot), bytesAsText(originals.get(file)), Lines.read(file)));
            }
            Map<String, String> artifacts = new LinkedHashMap<>();
            artifacts.put("log", log.toString());
            artifacts.put("field_changes", directory.resolve("field_changes.json").toString());
            if (patch.length() > 0) {
                Path patchFile = directory.resolve("field_transformations.patch");
                Lines.write(patchFile, patch.toString());
                artifacts.put("patch", patchFile.toString());
            }
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("mode", mode.cliName());
            json.put("changes", changes.stream().map(Change::toJson).toList());
            Json.writeFile(directory.resolve("field_changes.json"), json, true);
            long finals = changes.stream().filter(change -> change.kept() && change.change().equals("final")).count();
            long locals = changes.stream().filter(change -> change.kept() && change.change().equals("local")).count();
            List<String> notes = new ArrayList<>();
            notes.add("Made " + finals + " resource field(s) final and turned " + locals + " into local variables (" + mode.cliName() + ").");
            if (!dropped.isEmpty()) {
                notes.add("Undid the changes to " + dropped.size() + " file(s) that did not compile with them.");
            }
            return finish(directory, changed.stream().map(file -> relative(file, workspaceRoot)).toList(), notes, artifacts);
        } catch (IOException e) {
            throw new StageException("Field transformations could not write their files: " + e.getMessage(), e);
        }
    }

    /** Compiles the program; undoes the edits in files javac reports errors in, until it compiles. */
    private Set<Path> compileUntilClean(RunContext context, String classpath, List<String> language, Path sourcesFile, Map<Path, byte[]> before,
            Set<Path> edited, Path log, String title) throws StageException, IOException {
        Set<Path> undone = new HashSet<>();
        for (int round = 1; round <= MAX_REPAIR_ROUNDS; round++) {
            Path classes = Files.createTempDirectory("arodnap-fields-check-");
            CommandResult result;
            try {
                List<String> command = new ArrayList<>(List.of(context.jdk().javac().toString(), "-proc:none", "-nowarn", "-d", classes.toString(),
                        "-classpath", classpath));
                command.addAll(language);
                command.add("@" + sourcesFile.toAbsolutePath());
                result = run(context, Command.of(command, sourcesFile.toAbsolutePath().getParent()));
            } finally {
                Workspace.deleteTree(classes);
            }
            StageLog.append(log, title + " compile check " + round, result, "javac");
            if (result.succeeded()) {
                return undone;
            }
            Set<Path> failing = new HashSet<>();
            Matcher matcher = ERROR_FILE.matcher(result.stdout() + result.stderr());
            while (matcher.find()) {
                failing.add(FilePaths.real(Path.of(matcher.group(1))));
            }
            failing.retainAll(edited);
            failing.removeAll(undone);
            if (failing.isEmpty()) {
                // The errors are not in edited files; fail rather than report broken code as fine.
                throw new StageException("The program no longer compiles after " + title + " (see " + log + ").");
            }
            for (Path file : failing) {
                Files.write(file, before.get(file));
            }
            undone.addAll(failing);
        }
        throw new StageException("Could not get the program to compile after " + title + " (see " + log + ").");
    }

    private static CommandResult run(RunContext context, Command command) throws StageException {
        try {
            return context.runStageCommand(command);
        } catch (CommandException.TimedOut e) {
            throw new StageException(e.getMessage() + " (--stage-timeout)", e);
        } catch (CommandException e) {
            throw new StageException(e.getMessage(), e);
        }
    }

    /** Identifiers that appear as string literals anywhere: fields reflection could look up. */
    static List<String> stringLiteralNames(Iterable<byte[]> contents) {
        TreeSet<String> names = new TreeSet<>();
        for (byte[] data : contents) {
            Matcher matcher = IDENTIFIER_LITERAL.matcher(new String(data, StandardCharsets.UTF_8));
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return List.copyOf(names);
    }

    private static Map<Path, byte[]> snapshot(Set<Path> files) throws IOException {
        Map<Path, byte[]> snapshot = new LinkedHashMap<>();
        for (Path file : files) {
            snapshot.put(file, Files.readAllBytes(file));
        }
        return snapshot;
    }

    private static String bytesAsText(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static String relative(Path file, Path root) {
        return FilePaths.relativeTo(file, root).orElse(file.toString());
    }

    private static void writeLines(Path file, List<String> lines) throws IOException {
        StringBuilder text = new StringBuilder();
        lines.forEach(line -> text.append(line).append('\n'));
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static StageResult finish(Path directory, List<String> changed, List<String> notes, Map<String, String> artifacts)
            throws IOException {
        // It runs before the initial analysis, so no rerun is needed.
        return StageLog.writeResult(directory, new StageResult(NAME, !changed.isEmpty(), changed, false, artifacts, notes, true));
    }
}
