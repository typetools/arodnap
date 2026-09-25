package org.arodnap.engine.patch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatchApplierTest {
    @TempDir
    Path root;

    private Path write(String name, String text) throws IOException {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        Lines.write(path, text);
        return path;
    }

    private PatchApplier.Outcome apply(String patch) throws IOException {
        return PatchApplier.apply(root, patch, PatchApplier.Options.EXACT);
    }

    private static String lines(String prefix, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append(prefix).append(i).append('\n');
        }
        return text.toString();
    }

    @Test
    void appliesAChange() throws IOException {
        Path file = write("src/A.java", "class A {\n  int x;\n}\n");
        String patch = UnifiedDiff.create("src/A.java", Lines.read(file), "class A {\n  final int x = 1;\n}\n");

        PatchApplier.Outcome outcome = apply(patch);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.patched()).containsExactly("src/A.java");
        assertThat(outcome.messages()).containsExactly("patching file src/A.java");
        assertThat(Lines.read(file)).isEqualTo("class A {\n  final int x = 1;\n}\n");
    }

    @Test
    void placesAHunkWhereItsLinesMovedAfterAnEarlierEdit() throws IOException {
        String original = lines("line ", 40);
        String patch = UnifiedDiff.create("F.txt", original, original.replace("line 30\n", "line thirty\n"));
        Path file = write("F.txt", lines("new ", 5) + original);

        assertThat(apply(patch).ok()).isTrue();
        assertThat(Lines.read(file)).startsWith("new 0\n").contains("line thirty\n");
    }

    @Test
    void addsAndRemovesAFinalNewline() throws IOException {
        Path file = write("F.txt", "a\nb");
        assertThat(apply(UnifiedDiff.create("F.txt", "a\nb", "a\nb\nc\n")).ok()).isTrue();
        assertThat(Lines.read(file)).isEqualTo("a\nb\nc\n");

        assertThat(apply(UnifiedDiff.create("F.txt", "a\nb\nc\n", "a\nb\nc")).ok()).isTrue();
        assertThat(Lines.read(file)).isEqualTo("a\nb\nc");
    }

    @Test
    void keepsALoneCarriageReturnBeforeAMissingFinalNewline() throws IOException {
        Path file = write("F.txt", "a\r\nb\r\n");
        assertThat(apply(UnifiedDiff.create("F.txt", "a\r\nb\r\n", "a\r\nb\r\nc\r")).ok()).isTrue();
        assertThat(Lines.read(file)).isEqualTo("a\r\nb\r\nc\r");
    }

    @Test
    void createsAndDeletesFiles() throws IOException {
        write("gone.txt", "bye\n");
        String patch = "--- /dev/null\n+++ new.txt\n@@ -0,0 +1,2 @@\n+hello\n+world\n"
                + "--- gone.txt\n+++ /dev/null\n@@ -1 +0,0 @@\n-bye\n";

        PatchApplier.Outcome outcome = apply(patch);

        assertThat(outcome.errors()).isEmpty();
        assertThat(Lines.read(root.resolve("new.txt"))).isEqualTo("hello\nworld\n");
        assertThat(root.resolve("gone.txt")).doesNotExist();
    }

    @Test
    void writesNothingUnlessEveryFileApplies() throws IOException {
        Path a = write("A.txt", "one\n");
        Path b = write("B.txt", "two\n");
        String patch = UnifiedDiff.create("A.txt", "one\n", "ONE\n") + UnifiedDiff.create("B.txt", "something else\n", "TWO\n");

        PatchApplier.Outcome outcome = apply(patch);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.patched()).isEmpty();
        assertThat(outcome.errors()).singleElement().asString().startsWith("B.txt: hunk #1 FAILED");
        assertThat(List.of(Lines.read(a), Lines.read(b))).containsExactly("one\n", "two\n");
    }

    @Test
    void aCheckWritesNothing() throws IOException {
        Path file = write("A.txt", "one\n");

        PatchApplier.Outcome outcome = PatchApplier.apply(
                root, UnifiedDiff.create("A.txt", "one\n", "ONE\n"), new PatchApplier.Options(0, true, 0, false));

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.messages()).containsExactly("checking file A.txt");
        assertThat(Lines.read(file)).isEqualTo("one\n");
    }

    @Test
    void saysWhenAChangeSeemsAppliedAlready() throws IOException {
        write("A.txt", "ONE\ntwo\n");

        PatchApplier.Outcome outcome = apply(UnifiedDiff.create("A.txt", "one\ntwo\n", "ONE\ntwo\n"));

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.errors()).singleElement().asString().endsWith("(the change seems to be applied already)");
    }

    @Test
    void fuzzToleratesChangedContextAtTheEdgesOfAHunk() throws IOException {
        String original = "a\nb\nc\nd\ne\nf\ng\n";
        String patch = UnifiedDiff.create("F.txt", original, original.replace("d\n", "D\n"));
        Path file = write("F.txt", original.replace("a\n", "A\n").replace("g\n", "G\n"));

        assertThat(apply(patch).ok()).isFalse();
        assertThat(PatchApplier.apply(root, patch, new PatchApplier.Options(0, false, 1, false)).ok()).isTrue();
        assertThat(Lines.read(file)).isEqualTo("A\nb\nc\nD\ne\nf\nG\n");
    }

    @Test
    void ignoringWhitespaceMatchesReindentedLines() throws IOException {
        Path file = write("F.txt", "if (x)  {\n    y();\n}\n");
        String patch = UnifiedDiff.create("F.txt", "if (x) {\n  y();\n}\n", "if (x) {\n  z();\n}\n");

        assertThat(apply(patch).ok()).isFalse();
        assertThat(PatchApplier.apply(root, patch, new PatchApplier.Options(0, false, 0, true)).ok()).isTrue();
        assertThat(Lines.read(file)).contains("z();");
    }

    @Test
    void ignoringWhitespaceTreatsANoBreakSpaceAsWhitespaceLikeThePythonVersion() {
        // "a b" in UTF-8, one character per byte.
        String withNoBreakSpace = "aÂ b\n";
        assertThat(PatchApplier.collapseWhitespace(withNoBreakSpace)).isEqualTo("a b");
        // A lone 0xA0 byte is not valid UTF-8, so it is not whitespace.
        assertThat(PatchApplier.collapseWhitespace("a b\n")).isEqualTo("a b");
    }

    @Test
    void stripsLeadingPathComponentsAndIgnoresHeaderTimestamps() throws IOException {
        Path file = write("src/A.txt", "x\n");
        String patch = "--- a/src/A.txt\t2026-09-24 10:00:00\n+++ b/src/A.txt\t2026-09-24 10:00:01\n@@ -1 +1 @@\n-x\n+y\n";

        assertThat(PatchApplier.apply(root, patch, new PatchApplier.Options(1, false, 0, false)).ok()).isTrue();
        assertThat(Lines.read(file)).isEqualTo("y\n");
    }

    @Test
    void blankLinesAfterTheLastHunkAreNotContext() throws IOException {
        Path file = write("A.txt", "one\n");
        assertThat(apply(UnifiedDiff.create("A.txt", "one\n", "ONE\n") + "\n\n").ok()).isTrue();
        assertThat(Lines.read(file)).isEqualTo("ONE\n");
    }

    @Test
    void aMalformedPatchFailsWithAReason() throws IOException {
        assertThat(apply("not a patch\n").errors()).singleElement().asString().startsWith("malformed patch");
        assertThat(apply("--- A.txt\n+++ A.txt\n@@ -1,3 +1,3 @@\n x\n").errors())
                .containsExactly("malformed patch: patch ends in the middle of a hunk");
    }

    @Test
    void aWindowsStyleFileKeepsItsLineEndings() throws IOException {
        // The repair tools write "\n"; context keeps the file's bytes and inserted lines get "\r\n".
        Path file = write("A.java", "a\r\nb\r\nc\r\n");
        assertThat(apply(UnifiedDiff.create("A.java", "a\nb\nc\n", "a\nB\nx\nc\n")).ok()).isTrue();
        assertThat(Lines.read(file)).isEqualTo("a\r\nB\r\nx\r\nc\r\n");
    }

    @Test
    void aFileWithoutLineEndingsTakesThePatchs() throws IOException {
        Path file = write("New.java", "");
        assertThat(apply(UnifiedDiff.create("New.java", "", "a\r\nb\r\n")).ok()).isTrue();
        assertThat(Lines.read(file)).isEqualTo("a\r\nb\r\n");
    }

    @Test
    void onlyANewlineEndsALine() throws IOException {
        assertThat(Lines.split("a\u000cb\nc\r d\nlast")).containsExactly("a\u000cb\n", "c\r d\n", "last");
        String before = "one\u000c\ntwo \r three\nfour\n";
        String after = "one\u000c\ntwo \r three\nFOUR\n";
        Path file = write("F.java", before);

        assertThat(apply(UnifiedDiff.create("F.java", before, after)).ok()).isTrue();
        assertThat(Lines.read(file)).isEqualTo(after);
    }
}
