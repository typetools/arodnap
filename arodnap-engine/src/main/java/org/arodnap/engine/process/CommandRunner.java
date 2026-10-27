package org.arodnap.engine.process;

/**
 * Runs external commands. Every build, compiler and tool run goes through this one interface, so
 * tests can replay recorded runs instead of starting processes.
 */
@FunctionalInterface
public interface CommandRunner {
    /**
     * Runs the command to completion and captures its output. A non-zero exit code is a normal
     * result; only a command that cannot start or exceeds its limit throws.
     */
    CommandResult run(Command command) throws CommandException;

    /** Runs commands as real processes. */
    static CommandRunner processes() {
        return new ProcessCommandRunner();
    }
}
