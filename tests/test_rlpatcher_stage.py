import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from arodnap.patch_tool import PatchToolError, discover_patch_tool
from arodnap.runtime import CommandResult
from arodnap.stages.rlpatcher import StageExecutionError, _changes_code, run_rlpatcher_stage


def _has_gnu_patch() -> bool:
    try:
        discover_patch_tool(require_gnu=True, operation_label="test")
    except PatchToolError:
        return False
    return True


@unittest.skipUnless(_has_gnu_patch(), "GNU patch is required to apply materialized patches")
class RLPatcherStageTest(unittest.TestCase):
    def setUp(self) -> None:
        java_patcher = patch("arodnap.stages.rlpatcher.java_executable", return_value="java")
        java_patcher.start()
        self.addCleanup(java_patcher.stop)

    def test_successful_run_materializes_normalized_patch_and_stage_result(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            (
                workspace_root,
                diagnostics_path,
                inference_dir,
                fixes_path,
                debug_path,
                rlpatcher_jar,
                source_file,
            ) = self._make_inputs(temp_root)
            stage_output_dir = temp_root / "arodnap-out" / "stages" / "rlpatcher"

            def fake_run_stage_command(*, command: list[str], cwd: Path) -> CommandResult:
                raw_patch_path = cwd / "rlfixer.patch"
                raw_patch_path.write_text(
                    "\n".join(
                        [
                            "--- /tmp/orig-123.java\t2025-01-01 00:00:00 +0000",
                            f"+++ {source_file.resolve()}\t2025-01-01 00:00:00 +0000",
                            "@@ -1 +1 @@",
                            "-class App { String old = \"old\"; }",
                            "+class App { String value = \"new\"; }",
                        ]
                    )
                    + "\n"
                )
                return CommandResult(
                    command=tuple(command),
                    cwd=cwd.resolve(),
                    returncode=0,
                    stdout="✅ Patch applied successfully: App.java\n",
                    stderr="",
                )

            with patch("arodnap.stages.rlpatcher.run_stage_command", side_effect=fake_run_stage_command):
                result = run_rlpatcher_stage(
                    workspace_root=workspace_root,
                    diagnostics_path=diagnostics_path,
                    inference_dir=inference_dir,
                    fixes_path=fixes_path,
                    debug_path=debug_path,
                    stage_output_dir=stage_output_dir,
                    rlpatcher_jar=rlpatcher_jar,
                    source_root=workspace_root / "src" / "main" / "java",
                )

            self.assertTrue(result.success)
            self.assertTrue(result.changed)
            self.assertEqual(result.changed_files, ["src/main/java/com/example/App.java"])
            self.assertTrue(result.rerun_required)
            self.assertEqual(source_file.read_text(), 'class App { String value = "new"; }\n')
            self.assertEqual(result.artifacts["patch_manifest"], str((stage_output_dir / "patch_manifest.json").resolve()))
            self.assertEqual(result.artifacts["patch_dir"], str((stage_output_dir / "patches").resolve()))
            self.assertTrue((stage_output_dir / "stage_result.json").is_file())
            self.assertFalse((stage_output_dir / "rlfixer.patch").exists())

            manifest = json.loads((stage_output_dir / "patch_manifest.json").read_text())
            self.assertEqual(len(manifest["patches"]), 1)
            patch_entry = manifest["patches"][0]
            patch_path = Path(patch_entry["patch_file"])
            self.assertTrue(patch_path.is_file())
            patch_text = patch_path.read_text()
            self.assertIn("--- src/main/java/com/example/App.java\t", patch_text)
            self.assertIn("+++ src/main/java/com/example/App.java\t", patch_text)

        # outside the tempdir, result serialization was already written before cleanup

    def test_manifest_records_strip_level_changed_files_and_preimage_hashes(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            (
                workspace_root,
                diagnostics_path,
                inference_dir,
                fixes_path,
                debug_path,
                rlpatcher_jar,
                source_file,
            ) = self._make_inputs(temp_root)
            stage_output_dir = temp_root / "arodnap-out" / "stages" / "rlpatcher"
            original_hash = hashlib.sha256(source_file.read_bytes()).hexdigest()

            def fake_run_stage_command(*, command: list[str], cwd: Path) -> CommandResult:
                (cwd / "rlfixer.patch").write_text(
                    "\n".join(
                        [
                            "--- /tmp/orig-456.java\t2025-01-01 00:00:00 +0000",
                            f"+++ {source_file.resolve()}\t2025-01-01 00:00:00 +0000",
                            "@@ -1 +1 @@",
                            "-class App { String old = \"old\"; }",
                            "+class App { String value = \"new\"; }",
                        ]
                    )
                    + "\n"
                )
                return CommandResult(
                    command=tuple(command),
                    cwd=cwd.resolve(),
                    returncode=0,
                    stdout="✅ Patch applied successfully: App.java\n",
                    stderr="",
                )

            with patch("arodnap.stages.rlpatcher.run_stage_command", side_effect=fake_run_stage_command):
                run_rlpatcher_stage(
                    workspace_root=workspace_root,
                    diagnostics_path=diagnostics_path,
                    inference_dir=inference_dir,
                    fixes_path=fixes_path,
                    debug_path=debug_path,
                    stage_output_dir=stage_output_dir,
                    rlpatcher_jar=rlpatcher_jar,
                    source_root=workspace_root / "src" / "main" / "java",
                )

            manifest = json.loads((stage_output_dir / "patch_manifest.json").read_text())
            patch_entry = manifest["patches"][0]
            self.assertEqual(patch_entry["stage"], "rlpatcher")
            self.assertEqual(patch_entry["strip_level"], 0)
            self.assertEqual(patch_entry["target_root"], ".")
            self.assertEqual(patch_entry["changed_files"], ["src/main/java/com/example/App.java"])
            self.assertEqual(
                patch_entry["preimage_hashes"],
                {"src/main/java/com/example/App.java": original_hash},
            )
            self.assertTrue(patch_entry["applied_to_workspace"])

    def test_patch_that_conflicts_with_an_earlier_one_is_skipped_not_half_applied(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            (
                workspace_root,
                diagnostics_path,
                inference_dir,
                fixes_path,
                debug_path,
                rlpatcher_jar,
                source_file,
            ) = self._make_inputs(temp_root)
            # Two suggestions for the same file, materialized against the same original text.
            fixes_path.write_text(fixes_path.read_text() + fixes_path.read_text().replace("Line number 10", "Line number 11").replace("+10", "+11"))
            diagnostics_path.write_text(
                diagnostics_path.read_text()
                + f"{source_file.resolve()}:11: warning: [required.method.not.called] leak\n"
            )
            stage_output_dir = temp_root / "arodnap-out" / "stages" / "rlpatcher"
            replacements = iter(['String first = "1";', 'String second = "2";'])

            def fake_run_stage_command(*, command: list[str], cwd: Path) -> CommandResult:
                (cwd / "rlfixer.patch").write_text(
                    f"--- {source_file.resolve()}\n+++ {source_file.resolve()}\n@@ -1 +1 @@\n"
                    f"-class App {{ String old = \"old\"; }}\n+class App {{ {next(replacements)} }}\n"
                )
                return CommandResult(
                    command=tuple(command),
                    cwd=cwd.resolve(),
                    returncode=0,
                    stdout="Patch applied successfully\n",
                    stderr="",
                )

            with patch("arodnap.stages.rlpatcher.run_stage_command", side_effect=fake_run_stage_command):
                result = run_rlpatcher_stage(
                    workspace_root=workspace_root,
                    diagnostics_path=diagnostics_path,
                    inference_dir=inference_dir,
                    fixes_path=fixes_path,
                    debug_path=debug_path,
                    stage_output_dir=stage_output_dir,
                    rlpatcher_jar=rlpatcher_jar,
                    source_root=workspace_root / "src" / "main" / "java",
                )

            self.assertEqual(source_file.read_text(), 'class App { String first = "1"; }\n')
            self.assertEqual(list(source_file.parent.glob("*.rej")), [])
            manifest = json.loads((stage_output_dir / "patch_manifest.json").read_text())
            self.assertEqual([entry["applied_to_workspace"] for entry in manifest["patches"]], [True, False])
            self.assertIn("applied 1, skipped 1", result.notes[0])

    def test_noop_run_writes_empty_manifest_and_skips_subprocess(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            (
                workspace_root,
                diagnostics_path,
                inference_dir,
                _fixes_path,
                debug_path,
                rlpatcher_jar,
                _source_file,
            ) = self._make_inputs(temp_root)
            fixes_path = temp_root / "fixes.txt"
            fixes_path.write_text("")
            stage_output_dir = temp_root / "arodnap-out" / "stages" / "rlpatcher"

            with patch("arodnap.stages.rlpatcher.run_stage_command") as mocked_run:
                result = run_rlpatcher_stage(
                    workspace_root=workspace_root,
                    diagnostics_path=diagnostics_path,
                    inference_dir=inference_dir,
                    fixes_path=fixes_path,
                    debug_path=debug_path,
                    stage_output_dir=stage_output_dir,
                    rlpatcher_jar=rlpatcher_jar,
                    source_root=workspace_root / "src" / "main" / "java",
                )

            mocked_run.assert_not_called()
            self.assertTrue(result.success)
            self.assertFalse(result.changed)
            self.assertEqual(result.changed_files, [])
            self.assertFalse(result.rerun_required)
            manifest = json.loads((stage_output_dir / "patch_manifest.json").read_text())
            self.assertEqual(manifest, {"stage": "rlpatcher", "patches": []})

    def test_malformed_patch_fails_clearly(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            (
                workspace_root,
                diagnostics_path,
                inference_dir,
                fixes_path,
                debug_path,
                rlpatcher_jar,
                _source_file,
            ) = self._make_inputs(temp_root)
            stage_output_dir = temp_root / "arodnap-out" / "stages" / "rlpatcher"

            def fake_run_stage_command(*, command: list[str], cwd: Path) -> CommandResult:
                (cwd / "rlfixer.patch").write_text(
                    "\n".join(
                        [
                            "--- /tmp/orig-789.java\t2025-01-01 00:00:00 +0000",
                            "+++ /tmp/new-789.java\t2025-01-01 00:00:00 +0000",
                            "@@ -1 +1 @@",
                            "-old",
                            "+new",
                        ]
                    )
                    + "\n"
                )
                return CommandResult(
                    command=tuple(command),
                    cwd=cwd.resolve(),
                    returncode=0,
                    stdout="✅ Patch applied successfully: App.java\n",
                    stderr="",
                )

            with patch("arodnap.stages.rlpatcher.run_stage_command", side_effect=fake_run_stage_command):
                with self.assertRaisesRegex(
                    StageExecutionError,
                    "did not reference a file under workspace root",
                ):
                    run_rlpatcher_stage(
                        workspace_root=workspace_root,
                        diagnostics_path=diagnostics_path,
                        inference_dir=inference_dir,
                        fixes_path=fixes_path,
                        debug_path=debug_path,
                        stage_output_dir=stage_output_dir,
                        rlpatcher_jar=rlpatcher_jar,
                        source_root=workspace_root / "src" / "main" / "java",
                    )

    def test_rlpatcher_side_effects_on_workspace_files_are_undone(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            workspace_root, diagnostics_path, inference_dir, fixes_path, debug_path, rlpatcher_jar, source_file = (
                self._make_inputs(temp_root)
            )
            source_file.write_bytes(b"class App { String old = \"old\"; }")  # no trailing newline
            original = source_file.read_bytes()

            def fake_run_stage_command(*, command: list[str], cwd: Path) -> CommandResult:
                # RLPatcher restoring its backup with a trailing newline, reporting no patch.
                source_file.write_bytes(original + b"\n")
                return CommandResult(tuple(command), cwd, 0, "Patch applied successfully: App.java\n", "")

            with patch("arodnap.stages.rlpatcher.run_stage_command", side_effect=fake_run_stage_command):
                result = run_rlpatcher_stage(
                    workspace_root=workspace_root,
                    diagnostics_path=diagnostics_path,
                    inference_dir=inference_dir,
                    fixes_path=fixes_path,
                    debug_path=debug_path,
                    stage_output_dir=temp_root / "arodnap-out" / "stages" / "rlpatcher",
                    rlpatcher_jar=rlpatcher_jar,
                    source_root=workspace_root / "src" / "main" / "java",
                )

            self.assertEqual(source_file.read_bytes(), original)
            self.assertFalse(result.changed)

    def test_suggestions_rlpatcher_cannot_materialize_are_recorded_not_fatal(self) -> None:
        cases = {
            "no_change": CommandResult((), None, 0, "Patch applied successfully: App.java\n", ""),
            "rejected": CommandResult((), None, 0, "Patch failed (compilation check failed): App.java\n", ""),
            "unsupported": CommandResult((), None, 0, "", "Mixed patch types detected.\n"),
            "crashed": CommandResult((), None, 1, "", "Exception in thread main\n"),
        }
        for outcome, completed in cases.items():
            with self.subTest(outcome=outcome), tempfile.TemporaryDirectory() as temp_dir:
                temp_root = Path(temp_dir)
                workspace_root, diagnostics_path, inference_dir, fixes_path, debug_path, rlpatcher_jar, source_file = (
                    self._make_inputs(temp_root)
                )
                stage_output_dir = temp_root / "arodnap-out" / "stages" / "rlpatcher"

                with patch("arodnap.stages.rlpatcher.run_stage_command", return_value=completed):
                    result = run_rlpatcher_stage(
                        workspace_root=workspace_root,
                        diagnostics_path=diagnostics_path,
                        inference_dir=inference_dir,
                        fixes_path=fixes_path,
                        debug_path=debug_path,
                        stage_output_dir=stage_output_dir,
                        rlpatcher_jar=rlpatcher_jar,
                        source_root=workspace_root / "src" / "main" / "java",
                    )

                self.assertTrue(result.success)
                self.assertFalse(result.changed)
                self.assertIn("materialized 0 of 1", result.notes[0])
                manifest = json.loads((stage_output_dir / "patch_manifest.json").read_text())
                self.assertEqual(manifest["patches"], [])
                self.assertEqual(
                    manifest["fixes"],
                    [{"index": 1, "file": "com/example/App.java", "line": 10, "outcome": outcome}],
                )

    def _make_inputs(self, root: Path) -> tuple[Path, Path, Path, Path, Path, Path, Path]:
        workspace_root = root / "workspace"
        source_file = workspace_root / "src" / "main" / "java" / "com" / "example" / "App.java"
        source_file.parent.mkdir(parents=True)
        source_file.write_text('class App { String old = "old"; }\n')

        diagnostics_path = root / "diagnostics.txt"
        diagnostics_path.write_text(
            "\n".join(
                [
                    f"{source_file.resolve()}:10: warning: [required.method.not.called] leak",
                    "  sample warning block",
                ]
            )
            + "\n"
        )

        fixes_path = root / "fixes.txt"
        fixes_path.write_text(
            "\n".join(
                [
                    f"0] {source_file.resolve()}; Line number 10",
                    f"vim +10 {source_file.resolve()}",
                    "Introduce a safe close pattern.",
                    "--------------------------------------------",
                ]
            )
            + "\n"
        )

        debug_path = root / "debug.txt"
        debug_path.write_text("")

        inference_dir = root / "inference"
        inference_dir.mkdir()

        rlpatcher_jar = root / "RLPatcher.jar"
        rlpatcher_jar.write_bytes(b"fake-jar")

        return (
            workspace_root,
            diagnostics_path,
            inference_dir,
            fixes_path,
            debug_path,
            rlpatcher_jar,
            source_file,
        )


class ChangesCodeTest(unittest.TestCase):
    def test_whitespace_only_diffs_are_not_changes(self) -> None:
        self.assertFalse(_changes_code(""))
        self.assertFalse(
            _changes_code("--- A.java\n+++ A.java\n@@ -3 +3 @@\n-}\n+}\n\\ No newline at end of file\n")
        )
        self.assertFalse(_changes_code("--- A.java\n+++ A.java\n@@ -3,2 +3,2 @@\n-  int x;\n+\tint x;\n"))
        self.assertTrue(_changes_code("--- A.java\n+++ A.java\n@@ -3 +3 @@\n-  int x;\n+  final int x;\n"))


if __name__ == "__main__":
    unittest.main()
