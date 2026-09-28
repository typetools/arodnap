package org.arodnap.engine.process;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An external command: the build, javac, or one of the analysis and repair tools.
 *
 * @param arguments the program and its arguments
 * @param workingDirectory where it runs
 * @param environment variables to set on top of Arodnap's own environment
 * @param timeout a limit after which the command and every process it started are killed; empty
 *     means no limit, which is the default (the user sets limits explicitly)
 */
public record Command(List<String> arguments, Path workingDirectory, Map<String, String> environment, Optional<Duration> timeout) {
    public Command {
        arguments = List.copyOf(arguments);
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        environment = Map.copyOf(environment);
        Objects.requireNonNull(timeout, "timeout");
        if (arguments.isEmpty()) {
            throw new IllegalArgumentException("A command needs a program.");
        }
    }

    public static Command of(List<String> arguments, Path workingDirectory) {
        return new Command(arguments, workingDirectory, Map.of(), Optional.empty());
    }

    public Command withTimeout(Optional<Duration> limit) {
        return new Command(arguments, workingDirectory, environment, limit);
    }

    public Command withEnvironment(Map<String, String> variables) {
        return new Command(arguments, workingDirectory, variables, timeout);
    }

    /** The command line as it is logged: the arguments joined by spaces. */
    public String rendered() {
        return String.join(" ", arguments);
    }
}
