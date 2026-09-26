package org.arodnap.engine.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.arodnap.engine.Recorded;
import org.arodnap.engine.diagnostics.CheckerWarning;
import org.arodnap.engine.json.Json;
import org.junit.jupiter.api.Test;

/**
 * RLFixer's inputs and outputs from a real run of the coverage fixture, and what the Python version
 * made of them (python-rlfixer-io.json).
 */
class RlFixerFormatsTest {
    private static final List<CheckerWarning> WARNINGS = CheckerWarning.parseAll(Recorded.text("diagnostics/post_owning_field.txt"));

    private static JsonNode python() throws IOException {
        return Json.parse(Recorded.text("python-rlfixer-io.json"));
    }

    @Test
    void writesTheWarningsFileLikeThePythonVersion() throws IOException {
        RlFixerFormats.WarningsInput input = RlFixerFormats.warningsInput(WARNINGS, Recorded.SOURCE_ROOT);

        assertThat(input.fileContents()).isEqualTo(python().get("warnings_file").asText());
        assertThat(input.outsideSourceRoot()).isEmpty();
    }

    @Test
    void readsEveryFixFromTheReport() throws IOException {
        String fixes = Recorded.text("rlfixer/fixes.txt");
        List<RlFixerFormats.FixSuggestion> suggestions = RlFixerFormats.parseFixes(fixes, Recorded.SOURCE_ROOT);

        assertThat(suggestions).hasSize(python().get("fix_count").asInt()).hasSize(RlFixerFormats.countFixes(fixes));
        assertThat(suggestions).allSatisfy(suggestion -> assertThat(suggestion.relpath()).doesNotStartWith("/"));
    }

    @Test
    void readsTheDebugTable() throws IOException {
        RlFixerFormats.DebugTable table = RlFixerFormats.parseDebugTable(Recorded.text("rlfixer/debug.txt"));

        List<String> fixable = new ArrayList<>(table.fixable().stream().map(key -> key.relpath() + ":" + key.line()).toList());
        fixable.sort(null);
        List<String> expected = new ArrayList<>();
        python().get("fixable").forEach(node -> expected.add(node.asText()));
        assertThat(fixable).isEqualTo(expected);
        assertThat(table.lastStatus(new RlFixerFormats.Key("loop_fixes/EscapedTryCatchInLoop.java", 20))).contains(RlFixerFormats.DebugStatus.UNFIXABLE);
    }

    @Test
    void aWarningListedAgainAsADuplicateIsStillFixed() {
        // From pdfbox: RLFixer lists these warnings twice, first fixable, then as a duplicate.
        RlFixerFormats.DebugTable table = RlFixerFormats.parseDebugTable(Recorded.realProject("pdfbox", "debug-repeated-warnings.txt"));
        RlFixerFormats.Key document = new RlFixerFormats.Key("pdfbox/src/main/java/org/apache/pdfbox/pdmodel/PDDocument.java", 700);
        RlFixerFormats.Key embedder = new RlFixerFormats.Key("pdfbox/src/main/java/org/apache/pdfbox/pdmodel/font/PDType1FontEmbedder.java", 79);

        assertThat(table.fixable()).containsExactlyInAnyOrder(document, embedder);
        assertThat(table.lastStatus(document)).contains(RlFixerFormats.DebugStatus.DUPLICATE);
        List<RlFixerFormats.FixSuggestion> suggestions = List.of(
                new RlFixerFormats.FixSuggestion("/src/" + document.relpath(), document.relpath(), 700, "+++ Add following code at line 701"),
                new RlFixerFormats.FixSuggestion("/src/" + embedder.relpath(), embedder.relpath(), 79, "+++ Add following code at line 80"),
                new RlFixerFormats.FixSuggestion("/src/Other.java", "Other.java", 3, "+++ Add following code at line 4"));
        assertThat(RlFixerFormats.selectFixable(suggestions, table)).extracting(RlFixerFormats.FixSuggestion::key).containsExactly(document, embedder);
    }

    @Test
    void pairsFixableSuggestionsWithTheirLeakWarnings() throws IOException {
        List<RlFixerFormats.Match> matches = matches();

        List<String> pairs = matches.stream().map(match -> match.fix().relpath() + ":" + match.fix().line()).toList();
        List<String> expected = new ArrayList<>();
        python().get("matched").forEach(pair -> expected.add(pair.get(0).asText() + ":" + pair.get(1).asInt()));
        assertThat(pairs).isEqualTo(expected);
        assertThat(matches).allSatisfy(match -> assertThat(match.warning().isLeak()).isTrue());
    }

    @Test
    void writesRlPatchersPromptLikeThePythonVersion() throws IOException {
        RlFixerFormats.Match first = matches().get(0);
        assertThat(RlFixerFormats.rlpatcherPrompt(first.warning(), first.fix())).isEqualTo(python().get("first_prompt").asText());
    }

    @Test
    void aSuggestionThatNeedsNoEditIsRecognized() {
        String nothing = "NOTE: Resource escapes via return statement\nNothing to be done. No callers found for method with resource return";
        assertThat(new RlFixerFormats.FixSuggestion("/a/B.java", "B.java", 3, nothing).nothingToDo()).isTrue();
        String edit = nothing + "\n+++ Add following code at line 12";
        assertThat(new RlFixerFormats.FixSuggestion("/a/B.java", "B.java", 3, edit).nothingToDo()).isFalse();
    }

    private static List<RlFixerFormats.Match> matches() {
        List<RlFixerFormats.FixSuggestion> suggestions = RlFixerFormats.parseFixes(Recorded.text("rlfixer/fixes.txt"), Recorded.SOURCE_ROOT);
        return RlFixerFormats.matchFixesToWarnings(
                RlFixerFormats.selectFixable(suggestions, RlFixerFormats.parseDebugTable(Recorded.text("rlfixer/debug.txt"))), WARNINGS);
    }
}
