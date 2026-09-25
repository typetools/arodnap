package org.arodnap.engine.process;

import java.time.Duration;

/** A command could not run to completion. */
public class CommandException extends Exception {
    private static final long serialVersionUID = 1L;

    private final transient Command command;

    public CommandException(Command command, String message, Throwable cause) {
        super(message, cause);
        this.command = command;
    }

    public Command command() {
        return command;
    }

    /** The program could not be started, for example because it is not installed. */
    public static final class NotStarted extends CommandException {
        private static final long serialVersionUID = 1L;

        public NotStarted(Command command, Throwable cause) {
            super(command, "Failed to execute command in " + command.workingDirectory() + ": " + command.rendered()
                    + " (" + cause.getMessage() + ")", cause);
        }
    }

    /** The command ran longer than the user's limit; it and every process it started were killed. */
    public static final class TimedOut extends CommandException {
        private static final long serialVersionUID = 1L;

        private final Duration limit;
        private final String stdout;
        private final String stderr;

        public TimedOut(Command command, Duration limit, String stdout, String stderr) {
            super(command, "Command timed out after " + limit.toSeconds() + " seconds in " + command.workingDirectory()
                    + ": " + command.rendered(), null);
            this.limit = limit;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public Duration limit() {
            return limit;
        }

        public String stdout() {
            return stdout;
        }

        public String stderr() {
            return stderr;
        }
    }
}
