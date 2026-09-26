package org.arodnap.engine.pipeline;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Per-command limits the user set. There are none by default: a large project can legitimately
 * take hours, and the paper's evaluation limits do not carry over.
 *
 * @param build the project's build while Arodnap captures it
 * @param analysis each Checker Framework run (every inference round, every leak check) and each
 *     compile of the analyzed program
 * @param stage each repair tool run (close injector, owning-field fixer, RLFixer, and RLPatcher per
 *     suggestion)
 */
public record Timeouts(Optional<Duration> build, Optional<Duration> analysis, Optional<Duration> stage) {
    public static final Timeouts NONE = new Timeouts(Optional.empty(), Optional.empty(), Optional.empty());

    /** As report.json writes them: seconds or null. */
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("build_seconds", build.map(Duration::toSeconds).orElse(null));
        json.put("analysis_seconds", analysis.map(Duration::toSeconds).orElse(null));
        json.put("stage_seconds", stage.map(Duration::toSeconds).orElse(null));
        return json;
    }
}
