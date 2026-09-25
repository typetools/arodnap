package org.arodnap.engine.patch;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Diffs made by the Python version for random edits, Windows line endings, missing final newlines,
 * form feeds, non-ASCII text and files over 200 lines (where difflib skips "popular" lines when
 * starting a match). See difflib-cases.json for how they were generated.
 */
class UnifiedDiffTest {
    @TempDir
    Path root;

    static Stream<Arguments> recordedCases() throws IOException {
        JsonNode cases;
        try (InputStream in = UnifiedDiffTest.class.getResourceAsStream("/patch/difflib-cases.json")) {
            cases = new ObjectMapper().readTree(in).get("cases");
        }
        return IntStream.range(0, cases.size()).mapToObj(i -> Arguments.of(
                i, bytes(cases.get(i).get("old")), bytes(cases.get(i).get("new")), bytes(cases.get(i).get("diff"))));
    }

    /** The recorded bytes, one character per byte, as the patch code handles text. */
    private static String bytes(JsonNode base64) {
        return new String(Base64.getDecoder().decode(base64.asText()), StandardCharsets.ISO_8859_1);
    }

    @ParameterizedTest(name = "case {0}")
    @MethodSource("recordedCases")
    void createsTheSameDiffAsPythonDifflib(int number, String oldText, String newText, String expectedDiff) {
        assertThat(UnifiedDiff.create("src/F.java", oldText, newText)).isEqualTo(expectedDiff);
    }

    @ParameterizedTest(name = "case {0}")
    @MethodSource("recordedCases")
    void applyingTheDiffReproducesTheNewText(int number, String oldText, String newText, String diff) throws IOException {
        if (diff.isEmpty()) {
            return;
        }
        Path file = root.resolve("src/F.java");
        file.getParent().toFile().mkdirs();
        Lines.write(file, oldText);

        PatchApplier.Outcome outcome = PatchApplier.apply(root, diff, PatchApplier.Options.EXACT);

        assertThat(outcome.errors()).isEmpty();
        assertThat(Lines.read(file)).isEqualTo(newText);
    }
}
