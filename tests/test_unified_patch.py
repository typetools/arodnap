import difflib
import random
import tempfile
import unittest
from pathlib import Path

from arodnap.unified_patch import PatchParseError, apply_patch, parse_unified_diff


def _diff(path: str, old: str, new: str, *, context: int = 3) -> str:
    lines = []
    for line in difflib.unified_diff(old.splitlines(keepends=True), new.splitlines(keepends=True),
                                     fromfile=path, tofile=path, n=context):
        lines.append(line if line.endswith("\n") else line + "\n\\ No newline at end of file\n")
    return "".join(lines)


class UnifiedPatchTest(unittest.TestCase):
    def setUp(self) -> None:
        self._temp = tempfile.TemporaryDirectory()
        self.root = Path(self._temp.name)

    def tearDown(self) -> None:
        self._temp.cleanup()

    def write(self, name: str, text: str) -> Path:
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def test_applies_a_simple_change(self) -> None:
        path = self.write("src/A.java", "class A {\n  int x;\n}\n")
        outcome = apply_patch(self.root, _diff("src/A.java", path.read_text(), "class A {\n  final int x = 1;\n}\n"))
        self.assertTrue(outcome.ok, outcome.errors)
        self.assertEqual(path.read_text(), "class A {\n  final int x = 1;\n}\n")
        self.assertEqual(outcome.patched, ["src/A.java"])

    def test_hunks_find_their_place_when_lines_moved(self) -> None:
        original = "".join(f"line {i}\n" for i in range(40))
        edited = original.replace("line 30\n", "line thirty\n")
        patch_text = _diff("F.txt", original, edited)
        # An earlier edit added five lines at the top.
        path = self.write("F.txt", "".join(f"new {i}\n" for i in range(5)) + original)
        self.assertTrue(apply_patch(self.root, patch_text).ok)
        self.assertIn("line thirty\n", path.read_text())
        self.assertTrue(path.read_text().startswith("new 0\n"))

    def test_missing_final_newline_on_either_side(self) -> None:
        path = self.write("F.txt", "a\nb")
        self.assertTrue(apply_patch(self.root, _diff("F.txt", "a\nb", "a\nb\nc\n")).ok)
        self.assertEqual(path.read_text(), "a\nb\nc\n")
        self.assertTrue(apply_patch(self.root, _diff("F.txt", "a\nb\nc\n", "a\nb\nc")).ok)
        self.assertEqual(path.read_text(), "a\nb\nc")

    def test_creates_and_deletes_files(self) -> None:
        self.write("gone.txt", "bye\n")
        patch_text = (
            "--- /dev/null\n+++ new.txt\n@@ -0,0 +1,2 @@\n+hello\n+world\n"
            "--- gone.txt\n+++ /dev/null\n@@ -1 +0,0 @@\n-bye\n"
        )
        outcome = apply_patch(self.root, patch_text)
        self.assertTrue(outcome.ok, outcome.errors)
        self.assertEqual((self.root / "new.txt").read_text(), "hello\nworld\n")
        self.assertFalse((self.root / "gone.txt").exists())

    def test_nothing_is_written_unless_every_file_applies(self) -> None:
        a = self.write("A.txt", "one\n")
        b = self.write("B.txt", "two\n")
        patch_text = _diff("A.txt", "one\n", "ONE\n") + _diff("B.txt", "something else\n", "TWO\n")
        outcome = apply_patch(self.root, patch_text)
        self.assertFalse(outcome.ok)
        self.assertIn("B.txt: hunk #1 FAILED", outcome.errors[0])
        self.assertEqual((a.read_text(), b.read_text()), ("one\n", "two\n"))

    def test_check_only_writes_nothing(self) -> None:
        path = self.write("A.txt", "one\n")
        self.assertTrue(apply_patch(self.root, _diff("A.txt", "one\n", "ONE\n"), check_only=True).ok)
        self.assertEqual(path.read_text(), "one\n")

    def test_applied_patch_is_reported_as_already_applied(self) -> None:
        self.write("A.txt", "ONE\ntwo\n")
        outcome = apply_patch(self.root, _diff("A.txt", "one\ntwo\n", "ONE\ntwo\n"))
        self.assertFalse(outcome.ok)
        self.assertIn("applied already", outcome.errors[0])

    def test_fuzz_tolerates_changed_context_at_hunk_edges(self) -> None:
        original = "a\nb\nc\nd\ne\nf\ng\n"
        patch_text = _diff("F.txt", original, original.replace("d\n", "D\n"))
        path = self.write("F.txt", original.replace("a\n", "A\n").replace("g\n", "G\n"))
        self.assertFalse(apply_patch(self.root, patch_text).ok)
        self.assertTrue(apply_patch(self.root, patch_text, fuzz=1).ok)
        self.assertEqual(path.read_text(), "A\nb\nc\nD\ne\nf\nG\n")

    def test_ignore_whitespace(self) -> None:
        path = self.write("F.txt", "if (x)  {\n    y();\n}\n")
        patch_text = _diff("F.txt", "if (x) {\n  y();\n}\n", "if (x) {\n  z();\n}\n")
        self.assertFalse(apply_patch(self.root, patch_text).ok)
        self.assertTrue(apply_patch(self.root, patch_text, ignore_whitespace=True).ok)
        self.assertIn("z();", path.read_text())

    def test_strip_level_and_header_timestamps(self) -> None:
        path = self.write("src/A.txt", "x\n")
        patch_text = "--- a/src/A.txt\t2026-09-24 10:00:00\n+++ b/src/A.txt\t2026-09-24 10:00:01\n@@ -1 +1 @@\n-x\n+y\n"
        self.assertTrue(apply_patch(self.root, patch_text, strip_level=1).ok)
        self.assertEqual(path.read_text(), "y\n")

    def test_trailing_blank_lines_after_the_last_hunk_are_not_context(self) -> None:
        path = self.write("A.txt", "one\n")
        self.assertTrue(apply_patch(self.root, _diff("A.txt", "one\n", "ONE\n") + "\n\n").ok)
        self.assertEqual(path.read_text(), "ONE\n")

    def test_malformed_patches_fail_clearly(self) -> None:
        with self.assertRaises(PatchParseError):
            parse_unified_diff("not a patch\n")
        outcome = apply_patch(self.root, "--- A.txt\n+++ A.txt\n@@ -1,3 +1,3 @@\n x\n")
        self.assertFalse(outcome.ok)
        self.assertIn("malformed patch", outcome.errors[0])

    def test_random_edits_round_trip(self) -> None:
        rng = random.Random(7)
        for case in range(300):
            original = [f"line {rng.randrange(20)}\n" for _ in range(rng.randrange(0, 60))]
            edited = list(original)
            for _ in range(rng.randrange(1, 6)):
                operation = rng.choice(("insert", "delete", "replace"))
                position = rng.randrange(len(edited) + 1)
                if operation == "insert" or not edited:
                    edited[position:position] = [f"new {rng.randrange(1000)}\n" for _ in range(rng.randrange(1, 4))]
                elif operation == "delete":
                    del edited[min(position, len(edited) - 1)]
                else:
                    edited[min(position, len(edited) - 1)] = f"changed {rng.randrange(1000)}\n"
            old_text, new_text = "".join(original), "".join(edited)
            if rng.random() < 0.3 and new_text:
                new_text = new_text.rstrip("\n")
            path = self.write("R.txt", old_text)
            patch_text = _diff("R.txt", old_text, new_text, context=rng.choice((0, 1, 3)))
            if not patch_text:
                continue
            outcome = apply_patch(self.root, patch_text)
            self.assertTrue(outcome.ok, (case, outcome.errors))
            self.assertEqual(path.read_text(), new_text, case)


if __name__ == "__main__":
    unittest.main()
