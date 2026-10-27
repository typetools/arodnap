package org.arodnap.engine.process;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A command that ran to completion.
 *
 * @param command what ran
 * @param exitCode its exit code
 * @param stdout its standard output
 * @param stderr its standard error
 */
public record CommandResult(Command command, int exitCode, String stdout, String stderr) {
    public boolean succeeded() {
        return exitCode == 0;
    }

    /** Standard output followed by standard error, as the tools' messages are read. */
    public String output() {
        return stdout + stderr;
    }

    /**
     * The log block Arodnap writes for a command (tool, directory, limit, command line, exit code and
     * both outputs), the same shape the stage logs and diagnostics files have always had.
     */
    public String log(Optional<String> toolName) {
        List<String> sections = new ArrayList<>();
        toolName.ifPresent(name -> sections.add("TOOL: " + name));
        sections.add("CWD: " + command.workingDirectory());
        command.timeout().ifPresent(limit -> sections.add("TIMEOUT_SECONDS: " + limit.toSeconds()));
        sections.add("COMMAND: " + command.rendered());
        sections.add("EXIT_CODE: " + exitCode);
        sections.add("STDOUT:");
        sections.add(stdout);
        sections.add("STDERR:");
        sections.add(stderr);
        return String.join("\n", sections);
    }
}
