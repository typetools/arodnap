import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from arodnap.runtime import CommandResult
from arodnap.stages.rlpatcher import StageExecutionError, run_rlpatcher_stage


class RLPatcherStageTest(unittest.TestCase):
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
                )

            self.assertTrue(result.success)
            self.assertFalse(result.changed)
            self.assertEqual(result.changed_files, [])
            self.assertFalse(result.rerun_required)
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
                )

            manifest = json.loads((stage_output_dir / "patch_manifest.json").read_text())
            patch_entry = manifest["patches"][0]
            self.assertEqual(patch_entry["stage"], "rlpatcher")
            self.assertEqual(patch_entry["strip_level"], 0)
            self.assertEqual(patch_entry["target_root"], ".")
            self.assertEqual(patch_entry["changed_files"], ["src/main/java/com/example/App.java"])
            self.assertEqual(
                patch_entry["preimage_hashes"],
                {
                    "src/main/java/com/example/App.java": hashlib.sha256(
                        source_file.read_bytes()
                    ).hexdigest()
                },
            )

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
                    )

    def test_missing_raw_patch_fails_clearly(self) -> None:
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

            completed = CommandResult(
                command=("java", "-jar", str(rlpatcher_jar)),
                cwd=stage_output_dir.resolve(),
                returncode=0,
                stdout="✅ Patch applied successfully: App.java\n",
                stderr="",
            )

            with patch("arodnap.stages.rlpatcher.run_stage_command", return_value=completed):
                with self.assertRaisesRegex(StageExecutionError, "RLPatcher did not emit rlfixer.patch"):
                    run_rlpatcher_stage(
                        workspace_root=workspace_root,
                        diagnostics_path=diagnostics_path,
                        inference_dir=inference_dir,
                        fixes_path=fixes_path,
                        debug_path=debug_path,
                        stage_output_dir=stage_output_dir,
                        rlpatcher_jar=rlpatcher_jar,
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


if __name__ == "__main__":
    unittest.main()
