package org.arodnap.engine.patch;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The diffs Arodnap makes, case by case (diff-cases.json): edits, hunks, missing final newlines,
 * Windows line endings, form feeds, non-UTF-8 bytes, and long files, where difflib starts no match
 * on a line that makes up more than 1% of the file. The expected diffs are what Python's difflib
 * made for earlier releases, so patches stay the same.
 *
 * <p>A new case's expected diff comes from Python: {@code difflib.unified_diff(old, new, "src/F.java",
 * "src/F.java")} on the lines split at {@code "\n"} only, with {@code "\\ No newline at end of file"}
 * after a line that has none.
 */
class UnifiedDiffTest {
    @TempDir
    Path root;

    static Stream<Arguments> cases() throws IOException {
        JsonNode cases;
        try (InputStream in = UnifiedDiffTest.class.getResourceAsStream("/patch/diff-cases.json")) {
            cases = new ObjectMapper().readTree(in).get("cases");
        }
        List<Arguments> arguments = new ArrayList<>();
        for (JsonNode each : cases) {
            Charset encoding = Charset.forName(each.path("encoding").asText("UTF-8"));
            String newText = bytes(each.get("new"), encoding);
            arguments.add(Arguments.of(each.get("name").asText(), bytes(each.get("old"), encoding), newText, bytes(each.get("diff"), encoding),
                    each.has("applied") ? bytes(each.get("applied"), encoding) : newText));
        }
        return arguments.stream();
    }

    /** The lines' bytes in the case's encoding, one character per byte, as the patch code handles text. */
    private static String bytes(JsonNode lines, Charset encoding) {
        StringBuilder text = new StringBuilder();
        lines.forEach(line -> text.append(line.asText()));
        return new String(text.toString().getBytes(encoding), StandardCharsets.ISO_8859_1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void createsTheExpectedDiff(String name, String oldText, String newText, String expectedDiff, String applied) {
        assertThat(UnifiedDiff.create("src/F.java", oldText, newText)).isEqualTo(expectedDiff);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void applyingTheDiffGivesTheNewText(String name, String oldText, String newText, String diff, String applied) throws IOException {
        if (diff.isEmpty()) {
            return;
        }
        Path file = root.resolve("src/F.java");
        file.getParent().toFile().mkdirs();
        Lines.write(file, oldText);

        PatchApplier.Outcome outcome = PatchApplier.apply(root, diff, PatchApplier.Options.EXACT);

        assertThat(outcome.errors()).isEmpty();
        assertThat(Lines.read(file)).isEqualTo(applied);
    }
}
