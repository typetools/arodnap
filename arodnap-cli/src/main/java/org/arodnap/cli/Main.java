package org.arodnap.cli;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.arodnap.cli.capture.BuildRecording;
import org.arodnap.cli.capture.BuildRecordings;
import org.arodnap.engine.Version;
import org.arodnap.engine.apply.BundleApplier;
import org.arodnap.engine.doctor.Doctor;
import org.arodnap.engine.inputs.ProjectCapture;
import org.arodnap.engine.jdk.JdkLocator;
import org.arodnap.engine.pipeline.Engine;
import org.arodnap.engine.pipeline.RunFailedException;
import org.arodnap.engine.pipeline.RunSettings;
import org.arodnap.engine.pipeline.RunSettings.FieldTransformationMode;
import org.arodnap.engine.pipeline.Timeouts;
import org.arodnap.engine.process.CommandRunner;
import org.arodnap.engine.report.Summary;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** The {@code arodnap} command. */
@Command(name = "arodnap", mixinStandardHelpOptions = true, versionProvider = Main.VersionProvider.class,
        description = "Finds and repairs resource leaks in Java projects.",
        subcommands = {Main.Analyze.class, Main.Infer.class, Main.Repair.class, Main.Apply.class, Main.DoctorCommand.class})
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs a command line and returns the exit code. Everything after {@code --} is the project's
     * build command: {@code arodnap repair <repo> -- ./build.sh}.
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        List<String> arguments = new ArrayList<>(List.of(args));
        List<String> buildCommand = new ArrayList<>();
        int split = arguments.indexOf("--");
        if (split >= 0) {
            buildCommand.addAll(arguments.subList(split + 1, arguments.size()));
            arguments = new ArrayList<>(arguments.subList(0, split));
        }
        CommandLine commandLine = new CommandLine(new Main());
        commandLine.setOut(new java.io.PrintWriter(out, true));
        commandLine.setErr(new java.io.PrintWriter(err, true));
        commandLine.setExecutionStrategy(parseResult -> {
            CommandLine.ParseResult subcommand = parseResult.subcommand();
            if (subcommand == null) {
                if (parseResult.isUsageHelpRequested() || parseResult.isVersionHelpRequested()) {
                    return new CommandLine.RunLast().execute(parseResult);
                }
                commandLine.usage(err);
                return 2;
            }
            Object command = subcommand.commandSpec().userObject();
            if (subcommand.isUsageHelpRequested()) {
                subcommand.commandSpec().commandLine().usage(out);
                return 0;
            }
            if (command instanceof Base base) {
                base.buildCommand = buildCommand;
                base.out = out;
                base.err = err;
                if (!buildCommand.isEmpty() && command instanceof Apply) {
                    err.println("arodnap apply: apply does not take a build command");
                    return 2;
                }
                return base.execute();
            }
            return new CommandLine.RunLast().execute(parseResult);
        });
        return commandLine.execute(arguments.toArray(String[]::new));
    }

    /** Options every command shares. */
    abstract static class Base {
        @Option(names = "--out-dir", description = "where reports, logs and patches go (default: ./arodnap-out)")
        Path outDir = Path.of("arodnap-out");

        @Option(names = "--keep-workspace", description = "keep the copy of the project after the run, for debugging")
        boolean keepWorkspace;

        @Option(names = "--build-args", description = "an extra argument for the build tool (repeatable)")
        List<String> buildArgs = new ArrayList<>();

        @Option(names = "--compile-target", description = "the build target that compiles the main sources")
        String compileTarget;

        @Option(names = "--checker-framework", description = "a Checker Framework distribution directory (or its checker.jar) to use "
                + "instead of the bundled one; defaults to $ARODNAP_CHECKER_FRAMEWORK")
        Path checkerFramework;

        @Option(names = "--build-timeout", paramLabel = "SECONDS", description = "limit for the project's build while Arodnap captures it")
        Integer buildTimeout;

        @Option(names = "--analysis-timeout", paramLabel = "SECONDS", description = "limit for each Checker Framework run (every "
                + "inference iteration, every leak check) and the analysis compile")
        Integer analysisTimeout;

        @Option(names = "--stage-timeout", paramLabel = "SECONDS", description = "limit for each repair tool run (close injector, "
                + "owning-field fixer, RLFixer, and RLPatcher per suggestion)")
        Integer stageTimeout;

        @Parameters(index = "0", paramLabel = "REPO", description = "the project directory")
        Path repoRoot;

        List<String> buildCommand = List.of();
        PrintStream out = System.out;
        PrintStream err = System.err;

        abstract String name();

        FieldTransformationMode fieldTransformations() {
            return FieldTransformationMode.RESOURCES;
        }

        /** Runs the command; returns the exit code. */
        abstract int run(Engine engine, RunSettings settings, ProjectCapture capture) throws Exception;

        int execute() {
            RunSettings settings;
            Installation installation;
            try {
                Timeouts timeouts = new Timeouts(seconds("--build-timeout", buildTimeout), seconds("--analysis-timeout", analysisTimeout),
                        seconds("--stage-timeout", stageTimeout));
                settings = new RunSettings(name(), repoRoot, outDir, keepWorkspace, timeouts, fieldTransformations(), buildArgs,
                        Optional.ofNullable(compileTarget));
                Optional<Path> checker = Optional.ofNullable(checkerFramework)
                        .or(() -> Optional.ofNullable(System.getenv("ARODNAP_CHECKER_FRAMEWORK")).filter(value -> !value.isEmpty()).map(Path::of));
                installation = Installation.locate(checker);
            } catch (IllegalArgumentException | Installation.NotFoundException e) {
                err.println("arodnap: " + name() + " failed: " + e.getMessage());
                return 2;
            }
            CommandRunner runner = CommandRunner.processes();
            JdkLocator jdks = JdkLocator.fromEnvironment(runner);
            Engine engine = new Engine(installation.toolchain(), runner, jdks, out, Summary::cliApplyCommand);
            BuildRecording.Options options = new BuildRecording.Options(buildArgs, Optional.ofNullable(compileTarget), buildCommand,
                    settings.timeouts());
            ProjectCapture capture = BuildRecordings.select(installation.hooks(), options, runner, jdks);
            try {
                return run(engine, settings, capture);
            } catch (Exception e) {
                // Failures are expected outcomes for unsupported projects; report them without a stack
                // trace. ARODNAP_DEBUG=1 shows it.
                if ("1".equals(System.getenv("ARODNAP_DEBUG"))) {
                    e.printStackTrace(err);
                }
                err.println("arodnap: " + name() + " failed: " + e.getMessage());
                Path report = settings.outDir().resolve(name().equals("doctor") ? "doctor.json" : "report.json");
                if (Files.isRegularFile(report)) {
                    err.println("arodnap: details in " + report);
                }
                return 1;
            }
        }

        private static Optional<Duration> seconds(String option, Integer value) {
            if (value == null) {
                return Optional.empty();
            }
            if (value <= 0) {
                throw new IllegalArgumentException(option + ": expected a positive number of seconds, got " + value);
            }
            return Optional.of(Duration.ofSeconds(value));
        }
    }

    @Command(name = "analyze", description = "Run the Resource Leak Checker once, without inference.")
    static final class Analyze extends Base {
        @Override
        String name() {
            return "analyze";
        }

        @Override
        int run(Engine engine, RunSettings settings, ProjectCapture capture) throws RunFailedException {
            engine.analyze(settings, capture);
            return 0;
        }
    }

    @Command(name = "infer", description = "Run whole-program inference, then the Resource Leak Checker.")
    static final class Infer extends Base {
        @Override
        String name() {
            return "infer";
        }

        @Override
        int run(Engine engine, RunSettings settings, ProjectCapture capture) throws RunFailedException {
            engine.infer(settings, capture);
            return 0;
        }
    }

    @Command(name = "repair", description = "Repair resource leaks on a copy of the project and write one verified patch.")
    static final class Repair extends Base {
        @Option(names = "--field-transformations", paramLabel = "MODE", description = "make private fields final or local before analysis: "
                + "only fields that can hold a resource (resources, the default), every eligible field as in the paper (all), or none (off)")
        String fields = "resources";

        @Override
        String name() {
            return "repair";
        }

        @Override
        FieldTransformationMode fieldTransformations() {
            return switch (fields) {
                case "resources", "all", "off" -> FieldTransformationMode.fromCliName(fields);
                default -> throw new IllegalArgumentException("--field-transformations: expected resources, all or off, got " + fields);
            };
        }

        @Override
        int run(Engine engine, RunSettings settings, ProjectCapture capture) throws RunFailedException {
            engine.repair(settings, capture);
            return 0;
        }
    }

    @Command(name = "apply", description = "Apply a patch bundle from `repair` to the project.")
    static final class Apply extends Base {
        @Option(names = "--patch-dir", required = true, description = "the patches/ directory a repair wrote")
        Path patchDir;

        @Override
        String name() {
            return "apply";
        }

        @Override
        int run(Engine engine, RunSettings settings, ProjectCapture capture) throws Exception {
            BundleApplier.apply(settings.repoRoot(), patchDir.toAbsolutePath().normalize(), settings.outDir().resolve("logs").resolve("apply.log"),
                    settings.keepWorkspace());
            return 0;
        }
    }

    @Command(name = "doctor", description = "Check that Arodnap can run on the project.")
    static final class DoctorCommand extends Base {
        @Override
        String name() {
            return "doctor";
        }

        @Override
        int run(Engine engine, RunSettings settings, ProjectCapture capture) throws Exception {
            return new Doctor(engine.toolchain(), engine.jdks()).run(settings, capture, out) ? 0 : 1;
        }
    }

    static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {"arodnap " + Version.VERSION};
        }
    }
}
