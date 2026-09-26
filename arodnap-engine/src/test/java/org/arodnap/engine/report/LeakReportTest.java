package org.arodnap.engine.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.arodnap.engine.Recorded;
import org.arodnap.engine.analysis.Analysis;
import org.arodnap.engine.diagnostics.Diagnostic;
import org.arodnap.engine.json.Json;
import org.arodnap.model.StageResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The per-leak report of a real run of the coverage fixture, where every stage fixes something,
 * against the report an earlier Arodnap release wrote for the same run (expected-leaks.json).
 */
class LeakReportTest {
    private static final List<String> LABELS = List.of("initial", "post_close_injector", "post_owning_field", "final");

    @TempDir
    Path temp;

    @Test
    void followsEachLeakThroughTheRun() throws IOException {
        Path metadata = temp.resolve("adapter-metadata.json");
        Files.writeString(metadata, "{\"source_root\": \"" + Recorded.SOURCE_ROOT + "\"}\n");
        List<LeakReport.LabeledAnalysis> analyses = new ArrayList<>();
        for (String label : LABELS) {
            Path diagnostics = Recorded.copy("diagnostics/" + label + ".txt", temp);
            analyses.add(new LeakReport.LabeledAnalysis(label, new Analysis(label, Recorded.WORKSPACE, temp, temp, diagnostics, 0, temp, temp, temp,
                    metadata, Optional.empty(), Optional.empty(), List.of())));
        }
        Path out = Recorded.OUT.resolve("stages");
        Map<String, StageResult> stages = Map.of(
                "close_injector", stage("close_injector", Map.of("patch", out.resolve("close_injector/close_injector.patch").toString())),
                "owning_field", stage("owning_field", Map.of("patch", out.resolve("owning_field/owning_field.patch").toString())),
                "rlfixer", stage("rlfixer", Map.of("debug", Recorded.copy("rlfixer/debug.txt", temp).toString())),
                "rlpatcher", stage("rlpatcher", Map.of("patch_manifest", Recorded.copy("rlpatcher/patch_manifest.json", temp).toString())));

        Map<String, Object> leaks = LeakReport.build(analyses, Recorded.WORKSPACE,
                Map.of("post_close_injector", "close_injector", "post_owning_field", "owning_field", "final", "rlpatcher"),
                Optional.of("post_owning_field"), stages);

        ObjectNode expected = (ObjectNode) Json.parse(Recorded.text("expected-leaks.json"));
        expected.remove(List.of("field_changes", "html_report"));
        JsonNode actual = Json.parse(Json.write(leaks, true));
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void matchesAWarningAcrossRunsEvenWhenItsLineMoved() {
        Diagnostic before = new Diagnostic("A.java", 10, Diagnostic.LEAK_KEY, List.of("method close", "in", "java.io.InputStream", "reason"), "");
        Diagnostic moved = new Diagnostic("A.java", 14, Diagnostic.LEAK_KEY, List.of("method close", "in", "java.io.InputStream", "other reason"), "");
        Diagnostic other = new Diagnostic("A.java", 10, Diagnostic.LEAK_KEY, List.of("method close", "out", "java.io.OutputStream", "reason"), "");

        assertThat(LeakReport.matchRuns(List.of(before), List.of(other, moved))).containsExactlyEntriesOf(Map.of(1, 0));
    }

    private static StageResult stage(String name, Map<String, String> artifacts) {
        return new StageResult(name, true, List.of(), true, artifacts, List.of(), true);
    }
}
