package org.arodnap.engine.stages;

import java.util.Optional;
import org.arodnap.model.StageResult;

/** One step of the repair pipeline. */
public interface Stage {
    /** The stage's name in reports and output directories, e.g. {@code close_injector}. */
    String name();

    /**
     * The label of the analysis to run after this stage when it changed the sources, or empty when
     * nothing after it needs a new analysis.
     */
    default Optional<String> reanalysisLabel() {
        return Optional.empty();
    }

    StageResult run(StageContext context) throws StageException;
}
