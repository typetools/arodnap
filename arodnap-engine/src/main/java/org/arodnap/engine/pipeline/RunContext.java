package org.arodnap.engine.pipeline;

import java.time.Duration;
import java.util.Optional;
import org.arodnap.engine.inputs.ProjectCapture.CapturedProject;
import org.arodnap.engine.jdk.Jdk;
import org.arodnap.engine.process.Command;
import org.arodnap.engine.process.CommandException;
import org.arodnap.engine.process.CommandResult;
import org.arodnap.engine.process.CommandRunner;
import org.arodnap.engine.tools.Toolchain;
import org.arodnap.model.ProjectInputs;

/**
 * Everything a run's analyses and stages share.
 *
 * @param settings what the user asked for
 * @param toolchain where the tools are
 * @param runner runs every external command
 * @param jdk the JDK every tool and compile runs on
 * @param workspace the copy of the project being analyzed and repaired
 * @param layout where outputs go
 * @param captured the project's inputs and how they were learned
 */
public record RunContext(
        RunSettings settings,
        Toolchain toolchain,
        CommandRunner runner,
        Jdk jdk,
        Workspace workspace,
        OutputLayout layout,
        CapturedProject captured) {

    public ProjectInputs inputs() {
        return captured.inputs();
    }

    /** Runs a command with the analysis limit (Checker Framework runs and compiles of the program). */
    public CommandResult runAnalysisCommand(Command command) throws CommandException {
        return runner.run(command.withTimeout(settings.timeouts().analysis()));
    }

    /** Runs a repair tool with the stage limit. */
    public CommandResult runStageCommand(Command command) throws CommandException {
        return runner.run(command.withTimeout(settings.timeouts().stage()));
    }

    public Optional<Duration> stageTimeout() {
        return settings.timeouts().stage();
    }

    public Optional<Duration> analysisTimeout() {
        return settings.timeouts().analysis();
    }
}
