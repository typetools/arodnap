package org.arodnap.engine.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.arodnap.engine.Recorded;
import org.arodnap.engine.analysis.Analyzer;
import org.junit.jupiter.api.Test;

/** Checker Framework 4.2.3 output recorded from a real run of the coverage fixture. */
class DiagnosticTest {
    private static final String INITIAL = Recorded.text("diagnostics/initial.txt");

    @Test
    void splitsTheOutputIntoOneBlockPerWarning() {
        List<CheckerWarning> warnings = CheckerWarning.parseAll(INITIAL);

        // Stub-file warnings ("warning: /.../Logger.astub:(line 1,col 1)") are neither counted nor parsed.
        assertThat(INITIAL).contains("warning: " + "/work/checker-framework/stubs/Logger.astub");
        assertThat(warnings).hasSize(Analyzer.countWarnings(INITIAL));
        assertThat(warnings).allSatisfy(warning -> assertThat(warning.message()).startsWith(warning.file() + ":" + warning.line() + ": warning:"));
        assertThat(warnings.stream().filter(CheckerWarning::isLeak)).hasSize(26);
    }

    @Test
    void readsTheStructuredFieldsOfALeak() {
        List<Diagnostic> diagnostics = Diagnostic.parseAll(INITIAL, Recorded.WORKSPACE);
        Diagnostic leak = diagnostics.stream().filter(Diagnostic::isLeak).findFirst().orElseThrow();

        assertThat(leak.path()).startsWith("src/main/java/");
        assertThat(leak.fields()).hasSizeGreaterThanOrEqualTo(4);
        assertThat(leak.fields().get(0)).startsWith("method ");
        // Line numbers are not part of a warning's identity: repairs move lines.
        assertThat(leak.identity()).containsExactly(leak.path(), Diagnostic.LEAK_KEY, leak.fields().get(0), leak.fields().get(1),
                leak.fields().get(2));
    }

    @Test
    void recognizesAnOwningFieldThatMightBeOverwritten() {
        assertThat(CheckerWarning.parseAll(INITIAL).stream().filter(CheckerWarning::isOwningFieldOverwrite)).isNotEmpty();
    }
}
