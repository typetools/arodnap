package org.arodnap.engine.stages;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.arodnap.engine.analysis.Analysis;
import org.arodnap.engine.pipeline.RunContext;
import org.arodnap.model.StageResult;

/**
 * What a stage gets.
 *
 * @param run the run's shared context
 * @param analysis the latest analysis of the workspace
 * @param previous the results of the stages that ran before, in order
 */
public record StageContext(RunContext run, Analysis analysis, List<StageResult> previous) {
    public StageContext {
        previous = List.copyOf(previous);
    }

    /** The result of an earlier stage, by name. */
    public Optional<StageResult> previous(String stage) {
        return previous.stream().filter(result -> result.stage().equals(stage)).reduce((first, second) -> second);
    }

    /** This stage's output directory. */
    public Path stageDirectory(String stage) {
        return run.layout().stage(stage);
    }

    public Path workspaceRoot() {
        return run.workspace().root();
    }

    /**
     * The system properties the Java stage tools read to compile-check their edits: the analyzed
     * sources and class path, and the build's release level and encoding.
     */
    public List<String> compileProperties() {
        List<String> properties = new ArrayList<>();
        properties.add("-Darodnap.sourcesFile=" + analysis.sourceFiles().toAbsolutePath());
        properties.add("-Darodnap.classpathFile=" + analysis.classpathEntries().toAbsolutePath());
        analysis.release().ifPresent(level -> properties.add("-Darodnap.release=" + level));
        analysis.encoding().filter(name -> !name.isEmpty()).ifPresent(name -> properties.add("-Darodnap.encoding=" + name));
        return properties;
    }
}
