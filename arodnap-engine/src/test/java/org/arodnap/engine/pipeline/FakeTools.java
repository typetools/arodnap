package org.arodnap.engine.pipeline;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.arodnap.engine.jdk.JdkLocator;
import org.arodnap.engine.process.Command;
import org.arodnap.engine.process.CommandException;
import org.arodnap.engine.process.CommandResult;
import org.arodnap.engine.process.CommandRunner;
import org.arodnap.engine.tools.Toolchain;

/**
 * Stands in for every external command of a run: the JDK, javac, the Checker Framework and the repair
 * tools. Each fake does what the real tool does to the file system (writes classes, inferred
 * annotations, patches) and prints what the real tool prints, from a script the test sets up.
 * Commands are recorded, so a test can check what ran and in which order.
 */
final class FakeTools implements CommandRunner {
    /** What a fake tool does for one command: returns its exit code and output. */
    @FunctionalInterface
    interface Behavior {
        CommandResult run(Command command) throws IOException;
    }

    final Path directory;
    final Toolchain toolchain;
    final List<Command> commands = new ArrayList<>();
    /** Checker output for each leak check, in order; "{ROOT}" is the workspace root. */
    final Deque<String> diagnostics = new ArrayDeque<>();
    Behavior closeInjector = FakeTools::nothingToPatch;
    Behavior owningField = FakeTools::nothingToPatch;
    Behavior rlfixer = command -> ok(command, "SOURCE LEVEL FIXES\n");
    Behavior rlpatcher = command -> ok(command, "Patch failed\n");

    FakeTools(Path directory) throws IOException {
        this.directory = directory;
        Path tools = Files.createDirectories(directory.resolve("tools"));
        Path checker = tools.resolve("checker.jar");
        try (OutputStream out = Files.newOutputStream(checker); JarOutputStream jar = new JarOutputStream(out)) {
            jar.putNextEntry(new JarEntry("org/checkerframework/checker/resourceleak/ResourceLeakChecker.class"));
            jar.write(new byte[] {(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe, 0, 0, 0, 61}); // Java 17
            jar.closeEntry();
        }
        Files.createDirectories(directory.resolve("stubs"));
        List<Path> jars = new ArrayList<>();
        for (String name : List.of("close-injector", "owning-field-fixer", "rlfixer", "rlpatcher", "field-transformations", "error-prone",
                "error-prone-jdk17", "dataflow")) {
            Path jar = tools.resolve(name + ".jar");
            Files.writeString(jar, "");
            jars.add(jar);
        }
        Files.createDirectories(directory.resolve("jdk/bin"));
        Files.writeString(directory.resolve("jdk/bin/java"), "");
        toolchain = new Toolchain(checker, directory.resolve("stubs"), jars.get(0), jars.get(1), jars.get(2), jars.get(3), jars.get(4),
                jars.get(5), jars.get(6), jars.get(7), "4.2.3", List.of(17, 21));
    }

    JdkLocator jdks() {
        return new JdkLocator(this, Map.of("JAVA_HOME", directory.resolve("jdk").toString(), "PATH", ""));
    }

    /** The commands that ran, as short names: "javac", "wpi", "rlc", "error-prone", "close-injector", ... */
    List<String> ran() {
        return commands.stream().map(FakeTools::kind).toList();
    }

    @Override
    public CommandResult run(Command command) throws CommandException {
        commands.add(command);
        try {
            return switch (kind(command)) {
                case "java-settings" -> new CommandResult(command, 0, "",
                        "    java.home = " + directory.resolve("jdk") + "\n    java.specification.version = 21\n");
                case "java-version" -> new CommandResult(command, 0, "", "openjdk version \"21\" 2023-09-19\n");
                case "javac" -> compile(command);
                case "error-prone" -> ok(command, "");
                case "wpi" -> infer(command);
                case "rlc" -> ok(command, next(command));
                case "close-injector" -> closeInjector.run(command);
                case "owning-field-fixer" -> owningField.run(command);
                case "rlfixer" -> rlfixer.run(command);
                case "rlpatcher" -> rlpatcher.run(command);
                default -> throw new AssertionError("Unexpected command: " + command.rendered());
            };
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String kind(Command command) {
        List<String> args = command.arguments();
        String program = Path.of(args.get(0)).getFileName().toString();
        if (program.equals("java") && args.contains("-XshowSettings:properties")) {
            return "java-settings";
        }
        if (program.equals("java") && args.size() == 2 && args.get(1).equals("-version")) {
            return "java-version";
        }
        if (program.equals("javac")) {
            return args.stream().anyMatch(arg -> arg.startsWith("-Xplugin:ErrorProne")) ? "error-prone" : "javac";
        }
        int jar = args.indexOf("-jar");
        String tool = Path.of(args.get(jar + 1)).getFileName().toString().replace(".jar", "");
        if (tool.equals("checker")) {
            return args.contains("-Ainfer=ajava") ? "wpi" : "rlc";
        }
        return tool;
    }

    private String next(Command command) {
        if (diagnostics.isEmpty()) {
            throw new AssertionError("No scripted checker output left for " + command.rendered());
        }
        return diagnostics.removeFirst().replace("{ROOT}", command.workingDirectory().toString());
    }

    /** javac writes a class file for every source into -d. */
    private static CommandResult compile(Command command) throws IOException {
        Path output = Path.of(value(command, "-d"));
        Files.createDirectories(output.resolve("demo"));
        Files.writeString(output.resolve("demo/Demo.class"), "");
        return ok(command, "");
    }

    /** Whole-program inference writes the same annotations every round, so the second round is the fixpoint. */
    private static CommandResult infer(Command command) throws IOException {
        Path generated = command.workingDirectory().resolve("build/whole-program-inference/demo");
        Files.createDirectories(generated);
        Files.writeString(generated.resolve("Demo-org.checkerframework.checker.resourceleak.ResourceLeakChecker.ajava"), "class Demo {}\n");
        return ok(command, "");
    }

    static CommandResult nothingToPatch(Command command) {
        return ok(command, "");
    }

    /** A tool that writes {@code patch} where -Darodnap.patchFile points (with the workspace root as {ROOT}). */
    static Behavior writesPatch(Function<Path, String> patch) {
        return command -> {
            String property = command.arguments().stream().filter(arg -> arg.startsWith("-Darodnap.patchFile=")).findFirst().orElseThrow();
            Files.writeString(Path.of(property.substring(property.indexOf('=') + 1)), patch.apply(command.workingDirectory()));
            return ok(command, "");
        };
    }

    static CommandResult ok(Command command, String stdout) {
        return new CommandResult(command, 0, stdout, "");
    }

    static CommandResult crash(Command command) {
        return new CommandResult(command, 1, "", "Exception in thread \"main\" java.lang.StackOverflowError\n");
    }

    static String value(Command command, String option) {
        int index = command.arguments().indexOf(option);
        return command.arguments().get(index + 1);
    }
}
