package org.arodnap.engine.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.arodnap.engine.jdk.Jdk;
import org.arodnap.engine.pipeline.OutputLayout;
import org.arodnap.engine.pipeline.RunContext;
import org.arodnap.engine.pipeline.Workspace;
import org.arodnap.engine.process.Command;
import org.arodnap.engine.process.CommandException;
import org.arodnap.engine.process.CommandResult;
import org.arodnap.engine.tools.CheckerFramework;

/**
 * Whole-program inference for the Resource Leak Checker: the checker's {@code -Ainfer=ajava} mode
 * run to a fixpoint. Each round compiles the sources with the previous round's inferred
 * {@code .ajava} files and the loop stops when a round infers nothing new.
 *
 * <p>This is the loop the Checker Framework's {@code wpi.sh} delegates to do-like-javac
 * ({@code do_like_javac/tools/wpi.py}), with the same flags; Arodnap runs it on the inputs it
 * already has instead of rebuilding the project every round.
 */
final class WholeProgramInference {
    /** Flags do-like-javac adds to every round; the previous round's output is passed with -Aajava. */
    static final List<String> ITERATION_FLAGS = List.of("-Ainfer=ajava", "-Awarns");
    static final int MAX_ITERATIONS = 20;
    // The Checker Framework cannot write an .ajava file for a source whose comments contain a
    // Unicode-escaped lone surrogate (e.g. "\uD800" in Javadoc). That class gets no inferred
    // annotations; the rest of the program is unaffected.
    private static final Pattern AJAVA_WRITE_FAILURE = Pattern.compile("^error: Error while writing ajava file (\\S+)", Pattern.MULTILINE);
    private static final Pattern COMPILER_ERROR = Pattern.compile("(^|: )error: ");

    /** The inferred annotations, in the analysis's inference directory, and the files that could not be written. */
    record Result(Path inferenceDirectory, int iterations, List<String> incomplete) {}

    private final RunContext context;

    WholeProgramInference(RunContext context) {
        this.context = context;
    }

    Result run(OutputLayout.AnalysisFiles files) throws AnalysisException, IOException {
        Jdk jdk = context.jdk();
        String classpath = Analyzer.joinPaths(Analyzer.classpathEntries(files));
        Files.writeString(files.wpiLog(), "TOOL: wpi\nWORKSPACE: " + context.workspace().root() + "\nJDK: " + jdk.home() + " ("
                + jdk.majorVersion() + ", from " + jdk.source() + ")\n", StandardCharsets.UTF_8);
        Path temp = Files.createTempDirectory("arodnap-wpi-");
        try {
            // javac writes -Ainfer output to <working directory>/build/whole-program-inference.
            Path cwd = Files.createDirectories(temp.resolve("cwd"));
            Path generated = cwd.resolve("build").resolve("whole-program-inference");
            Path previous = null;
            Path current = null;
            Set<String> incomplete = new TreeSet<>();
            int iteration = 1;
            for (; iteration <= MAX_ITERATIONS; iteration++) {
                Workspace.deleteTree(generated);
                incomplete.addAll(runIteration(files, classpath, previous, cwd, temp.resolve("classes" + iteration), iteration));
                current = temp.resolve("iteration" + iteration);
                if (Files.isDirectory(generated)) {
                    Files.move(generated, current);
                } else {
                    Files.createDirectories(current);
                }
                if (previous != null && sameTree(previous, current)) {
                    break;
                }
                previous = current;
            }
            if (iteration > MAX_ITERATIONS) {
                throw new AnalysisException("WPI did not reach a fixpoint after " + MAX_ITERATIONS + " iterations. See log: " + files.wpiLog());
            }
            StringBuilder footer = new StringBuilder("\nFIXPOINT_AFTER_ITERATIONS: " + iteration + "\n");
            incomplete.forEach(ajava -> footer.append("INCOMPLETE_INFERENCE: ").append(ajava).append('\n'));
            Files.writeString(files.wpiLog(), footer, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            Workspace.deleteTree(files.inferenceDirectory());
            Workspace.copyDirectory(current, files.inferenceDirectory());
            return new Result(files.inferenceDirectory(), iteration, List.copyOf(incomplete));
        } finally {
            Workspace.deleteTree(temp);
        }
    }

    private Set<String> runIteration(OutputLayout.AnalysisFiles files, String classpath, Path previous, Path cwd, Path classes, int iteration)
            throws AnalysisException, IOException {
        Files.createDirectories(classes);
        List<String> command = new ArrayList<>(CheckerFramework.javacCommand(context.toolchain(), context.jdk()));
        command.addAll(List.of("-processor", CheckerFramework.RESOURCE_LEAK_CHECKER));
        command.addAll(ITERATION_FLAGS);
        if (previous != null) {
            command.add("-Aajava=" + previous);
        }
        command.addAll(Jdk.languageOptions(context.inputs().release(), context.inputs().encoding()));
        command.addAll(List.of("-classpath", classpath, "-d", classes.toString(), "@" + files.sourceFiles()));
        CommandResult result;
        try {
            result = context.runAnalysisCommand(Command.of(command, cwd));
        } catch (CommandException.TimedOut e) {
            throw new AnalysisException("WPI iteration " + iteration + " exceeded --analysis-timeout. " + e.getMessage(), e);
        } catch (CommandException e) {
            throw new AnalysisException(e.getMessage(), e);
        }
        Files.writeString(files.wpiLog(), "\n== WPI iteration " + iteration + " ==\n" + result.log(Optional.empty()), StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        String output = result.stdout() + result.stderr();
        Set<String> ajavaFailures = new TreeSet<>();
        Matcher failure = AJAVA_WRITE_FAILURE.matcher(output);
        while (failure.find()) {
            ajavaFailures.add(failure.group(1));
        }
        boolean otherErrors = output.lines().anyMatch(line -> COMPILER_ERROR.matcher(line).find() && !AJAVA_WRITE_FAILURE.matcher(line).lookingAt());
        if (!result.succeeded() && (otherErrors || ajavaFailures.isEmpty())) {
            throw new AnalysisException("WPI iteration " + iteration + " failed to compile. See log: " + files.wpiLog());
        }
        return ajavaFailures;
    }

    /** True when both directories hold the same files with the same bytes. */
    static boolean sameTree(Path left, Path right) throws IOException {
        Map<String, Path> leftFiles = files(left);
        Map<String, Path> rightFiles = files(right);
        if (!leftFiles.keySet().equals(rightFiles.keySet())) {
            return false;
        }
        for (Map.Entry<String, Path> entry : leftFiles.entrySet()) {
            if (!Arrays.equals(Files.readAllBytes(entry.getValue()), Files.readAllBytes(rightFiles.get(entry.getKey())))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Path> files(Path root) throws IOException {
        Map<String, Path> files = new HashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile).forEach(path -> files.put(root.relativize(path).toString(), path));
        }
        return files;
    }
}
