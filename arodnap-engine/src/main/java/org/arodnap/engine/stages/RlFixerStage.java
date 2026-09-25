package org.arodnap.engine.stages;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.arodnap.engine.diagnostics.CheckerWarning;
import org.arodnap.engine.files.FilePaths;
import org.arodnap.engine.process.Command;
import org.arodnap.engine.process.CommandException;
import org.arodnap.engine.process.CommandResult;
import org.arodnap.engine.tools.RlFixerFormats;
import org.arodnap.model.StageResult;

/**
 * RLFixer: finds, for each leak, where the resource can be released and how (the paper's
 * wrapper-aware fix analysis on WALA). It proposes fixes as text; RLPatcher turns them into edits.
 *
 * <p>RLFixer's contract: {@code -projectDir} is the source root, {@code -srcFiles} lists sources
 * relative to it, {@code -warningsFile} names files the same way, and {@code -classpath} is the
 * path list WALA loads as application code (the compiled program plus its dependencies).
 */
public final class RlFixerStage implements Stage {
    public static final String NAME = "rlfixer";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public StageResult run(StageContext context) throws StageException {
        Path directory = context.stageDirectory(NAME);
        Path log = directory.resolve("stage.log");
        Path fixes = directory.resolve("fixes.txt");
        Path debug = directory.resolve("debug.txt");
        Path inputs = directory.resolve("inputs");
        Path sourceRoot = FilePaths.real(context.run().inputs().sourceRoot());
        try {
            requireDirectory(context.workspaceRoot(), "workspace root");
            requireDirectory(sourceRoot, "source root");
            requireDirectory(context.analysis().inferenceDirectory(), "inference directory");
            requireFile(context.analysis().diagnostics(), "diagnostics file");
            requireFile(context.analysis().sourceFiles(), "source files file");
            requireFile(context.analysis().appClasses(), "app classes file");
            requireFile(context.analysis().classpathEntries(), "classpath entries file");
            requireFile(context.run().toolchain().rlfixerJar(), "RLFixer jar");
            Files.createDirectories(inputs);
            StageLog.reset(log);

            List<CheckerWarning> warnings = CheckerWarning.parseAll(read(context.analysis().diagnostics()));
            RlFixerFormats.WarningsInput warningsInput = RlFixerFormats.warningsInput(warnings, sourceRoot);
            Path warningsFile = inputs.resolve("warnings.txt");
            Files.writeString(warningsFile, warningsInput.fileContents(), StandardCharsets.UTF_8);
            List<String> notes = new ArrayList<>();
            for (CheckerWarning skipped : warningsInput.outsideSourceRoot()) {
                notes.add("Skipped leak warning outside source root " + sourceRoot + ": " + skipped.file() + ":" + skipped.line());
            }

            if (warningsInput.isEmpty()) {
                Files.writeString(fixes, "", StandardCharsets.UTF_8);
                Files.writeString(debug, "", StandardCharsets.UTF_8);
                notes.add("No resource leak warnings to repair.");
            } else {
                Path sources = inputs.resolve("sources.txt");
                Files.writeString(sources, relativeSources(context.analysis().sourceFiles(), sourceRoot), StandardCharsets.UTF_8);
                Path appClasses = inputs.resolve("app_classes.txt");
                Files.writeString(appClasses, read(context.analysis().appClasses()), StandardCharsets.UTF_8);
                String classpath = String.join(File.pathSeparator, Files.readAllLines(context.analysis().classpathEntries()).stream()
                        .map(String::strip).filter(line -> !line.isEmpty()).toList());
                List<String> command = List.of(context.run().jdk().java().toString(), "-jar", context.run().toolchain().rlfixerJar().toString(),
                        "-classpath", classpath, "-warningsFile", warningsFile.toString(), "-appClasses", appClasses.toString(),
                        "-projectDir", sourceRoot.toString(), "-srcFiles", sources.toString(), "-debugOutput", debug.toString(),
                        "-wpiOutDir", context.analysis().inferenceDirectory().toString());
                CommandResult result;
                try {
                    result = context.run().runStageCommand(Command.of(command, directory));
                } catch (CommandException.TimedOut e) {
                    throw new StageException(e.getMessage() + " (--stage-timeout)", e);
                } catch (CommandException e) {
                    throw new StageException(e.getMessage(), e);
                }
                StageLog.append(log, "rlfixer", result, NAME);
                if (!result.succeeded()) {
                    throw new StageException("RLFixer failed. See log: " + log);
                }
                if (!result.stdout().contains(RlFixerFormats.SOURCE_LEVEL_FIXES_MARKER)) {
                    throw new StageException("RLFixer did not produce a fixes report. See log: " + log);
                }
                Files.writeString(fixes, result.stdout(), StandardCharsets.UTF_8);
                notes.add("RLFixer proposed " + RlFixerFormats.countFixes(result.stdout()) + " fix(es) for " + warningsInput.lines().size()
                        + " leak warning(s).");
            }
            for (Path artifact : List.of(fixes, debug)) {
                if (!Files.isRegularFile(artifact)) {
                    throw new StageException("RLFixer stage did not produce its " + artifact.getFileName().toString().replace(".txt", "")
                            + " artifact: " + artifact);
                }
            }
            Map<String, String> artifacts = new LinkedHashMap<>();
            artifacts.put("log", log.toString());
            artifacts.put("fixes", fixes.toString());
            artifacts.put("debug", debug.toString());
            artifacts.put("inputs", inputs.toString());
            return StageLog.writeResult(directory, new StageResult(NAME, false, List.of(), false, artifacts, notes, true));
        } catch (IOException e) {
            throw new StageException("RLFixer stage could not write its files: " + e.getMessage(), e);
        }
    }

    private static String relativeSources(Path sourceFiles, Path sourceRoot) throws IOException, StageException {
        StringBuilder relative = new StringBuilder();
        for (String line : Files.readAllLines(sourceFiles, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            Optional<String> path = FilePaths.relativeTo(Path.of(line.strip()), sourceRoot);
            if (path.isEmpty()) {
                throw new StageException("Source file " + line.strip() + " is outside source root " + sourceRoot + ".");
            }
            if (relative.length() > 0) {
                relative.append('\n');
            }
            relative.append(path.get());
        }
        return relative.append('\n').toString();
    }

    static String read(Path file) throws IOException {
        // Tool output is UTF-8; like the Python version, undecodable bytes become replacement characters.
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static void requireFile(Path path, String label) throws StageException {
        if (!Files.isRegularFile(path)) {
            throw new StageException("Missing " + label + " for RLFixer stage: " + path);
        }
    }

    private static void requireDirectory(Path path, String label) throws StageException {
        if (!Files.isDirectory(path)) {
            throw new StageException("Missing " + label + " for RLFixer stage: " + path);
        }
    }
}
