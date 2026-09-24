import tempfile
import unittest
from pathlib import Path

from arodnap.patch_tool import PatchToolError, append_patch_execution_log, discover_patch_tool, run_patch


class PatchToolTest(unittest.TestCase):
    def test_the_built_in_applier_needs_no_patch_program(self) -> None:
        tool = discover_patch_tool(require_gnu=True, operation_label="apply")
        self.assertEqual((tool.binary, tool.flavor), ("arodnap built-in", "builtin"))

    def test_dry_run_checks_without_writing_and_apply_writes(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            target = root / "src" / "A.java"
            target.parent.mkdir(parents=True)
            target.write_text("class A {}\n")
            patch_path = root / "change.patch"
            patch_path.write_text("--- src/A.java\n+++ src/A.java\n@@ -1 +1 @@\n-class A {}\n+final class A {}\n")

            check = run_patch(cwd=root, patch_path=patch_path, strip_level=0, check_only=True)
            self.assertEqual(check.completed.returncode, 0)
            self.assertIn("--dry-run", check.command)
            self.assertEqual(target.read_text(), "class A {}\n")

            applied = run_patch(cwd=root, patch_path=patch_path, strip_level=0, check_only=False)
            self.assertEqual(applied.completed.returncode, 0)
            self.assertEqual(target.read_text(), "final class A {}\n")

            log = root / "patch.log"
            append_patch_execution_log(log, title="apply", execution=applied)
            self.assertIn("PATCH_BINARY: arodnap built-in", log.read_text())
            self.assertIn("patching file src/A.java", log.read_text())

    def test_failures_are_reported_with_exit_code_and_message(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "A.java").write_text("class B {}\n")
            patch_path = root / "change.patch"
            patch_path.write_text("--- A.java\n+++ A.java\n@@ -1 +1 @@\n-class A {}\n+final class A {}\n")
            result = run_patch(cwd=root, patch_path=patch_path, strip_level=0, check_only=True).completed
        self.assertEqual(result.returncode, 1)
        self.assertIn("A.java: hunk #1 FAILED at 1", result.stderr)

    def test_unreadable_patch_file_fails_clearly(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            with self.assertRaisesRegex(PatchToolError, "Cannot read patch"):
                run_patch(cwd=Path(temp_dir), patch_path=Path(temp_dir) / "missing.patch", strip_level=0, check_only=True)


if __name__ == "__main__":
    unittest.main()
