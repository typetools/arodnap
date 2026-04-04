import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from arodnap.runtime import CommandResult
from arodnap.contracts import StageResult
from arodnap.stages.rlfixer import StageExecutionError, run_rlfixer_stage


class RLFixerStageTest(unittest.TestCase):
    def test_successful_run_writes_outputs_and_stage_result(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            workspace_root, diagnostics_path, inference_dir, bundle_root = self._make_inputs(
                temp_root,
                bundle_root=temp_root / "external-bundle",
            )
            stage_output_dir = temp_root / "arodnap-out" / "stages" / "rlfixer"

            def fake_run_stage_command(*, command: list[str], cwd: Path) -> CommandResult:
                output_dir = Path(command[command.index("--output") + 1])
                debug_dir = Path(command[command.index("--debug_output") + 1])
                output_dir.mkdir(parents=True, exist_ok=True)
                debug_dir.mkdir(parents=True, exist_ok=True)
                (output_dir / "compat_bundle.txt").write_text("fix suggestion\n")
                (debug_dir / "compat_bundle.txt").write_text("debug info\n")
                return CommandResult(
                    command=tuple(command),
                    cwd=cwd.resolve(),
                    returncode=0,
                    stdout="rlfixer ok\n",
                    stderr="",
                )

            with patch("arodnap.stages.rlfixer.run_stage_command", side_effect=fake_run_stage_command):
                result = run_rlfixer_stage(
                    workspace_root=workspace_root,
                    diagnostics_path=diagnostics_path,
                    inference_dir=inference_dir,
                    compatibility_bundle_root=bundle_root,
                    stage_output_dir=stage_output_dir,
                )

            staged_metadata_path = (stage_output_dir / "compat_bundle" / "metadata.json").resolve()
            self.assertEqual(
                result,
                StageResult(
                    stage="rlfixer",
                    changed=False,
                    changed_files=[],
                    rerun_required=False,
                    artifacts={
                        "log": str((stage_output_dir / "stage.log").resolve()),
                        "fixes": str((stage_output_dir / "fixes.txt").resolve()),
                        "debug": str((stage_output_dir / "debug.txt").resolve()),
                        "compatibility_bundle_metadata": str(staged_metadata_path),
                    },
                    notes=["RLFixer completed without mutating workspace sources."],
                    success=True,
                ),
            )
            self.assertTrue(staged_metadata_path.is_file())
            self.assertEqual((stage_output_dir / "fixes.txt").read_text(), "fix suggestion\n")
            self.assertEqual((stage_output_dir / "debug.txt").read_text(), "debug info\n")
            stage_result_payload = json.loads((stage_output_dir / "stage_result.json").read_text())
            self.assertEqual(stage_result_payload, result.to_dict())

    def test_successful_run_without_runner_files_still_preserves_empty_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            workspace_root, diagnostics_path, inference_dir, bundle_root = self._make_inputs(temp_root)
            stage_output_dir = temp_root / "arodnap-out" / "stages" / "rlfixer"
            completed = CommandResult(
                command=("python3",),
                cwd=Path.cwd(),
                returncode=0,
                stdout="",
                stderr="",
            )

            with patch("arodnap.stages.rlfixer.run_stage_command", return_value=completed):
                result = run_rlfixer_stage(
                    workspace_root=workspace_root,
                    diagnostics_path=diagnostics_path,
                    inference_dir=inference_dir,
                    compatibility_bundle_root=bundle_root,
                    stage_output_dir=stage_output_dir,
                )

            self.assertFalse(result.changed)
            self.assertEqual(result.changed_files, [])
            self.assertFalse(result.rerun_required)
            self.assertTrue((stage_output_dir / "fixes.txt").is_file())
            self.assertTrue((stage_output_dir / "debug.txt").is_file())
            self.assertEqual((stage_output_dir / "fixes.txt").read_text(), "")
            self.assertEqual((stage_output_dir / "debug.txt").read_text(), "")

    def test_nonzero_runner_exit_fails_with_logged_error(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            workspace_root, diagnostics_path, inference_dir, bundle_root = self._make_inputs(temp_root)
            stage_output_dir = temp_root / "arodnap-out" / "stages" / "rlfixer"
            completed = CommandResult(
                command=("python3", "RLFixerRunner.py"),
                cwd=Path.cwd(),
                returncode=1,
                stdout="",
                stderr="runner failed\n",
            )

            with patch("arodnap.stages.rlfixer.run_stage_command", return_value=completed):
                with self.assertRaisesRegex(StageExecutionError, "RLFixer stage failed. See log:"):
                    run_rlfixer_stage(
                        workspace_root=workspace_root,
                        diagnostics_path=diagnostics_path,
                        inference_dir=inference_dir,
                        compatibility_bundle_root=bundle_root,
                        stage_output_dir=stage_output_dir,
                    )

            self.assertTrue((stage_output_dir / "stage.log").is_file())
            self.assertIn("EXIT_CODE: 1", (stage_output_dir / "stage.log").read_text())

    def test_missing_bundle_fails_clearly(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            workspace_root = temp_root / "workspace"
            workspace_root.mkdir()
            diagnostics_path = temp_root / "diagnostics.txt"
            diagnostics_path.write_text("warning\n")
            inference_dir = temp_root / "inference"
            inference_dir.mkdir()

            with self.assertRaisesRegex(StageExecutionError, "Missing RLFixer compatibility bundle root"):
                run_rlfixer_stage(
                    workspace_root=workspace_root,
                    diagnostics_path=diagnostics_path,
                    inference_dir=inference_dir,
                    compatibility_bundle_root=temp_root / "missing-bundle",
                    stage_output_dir=temp_root / "arodnap-out" / "stages" / "rlfixer",
                )

    def test_malformed_bundle_metadata_fails_clearly(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            workspace_root, diagnostics_path, inference_dir, bundle_root = self._make_inputs(temp_root)
            (bundle_root / "metadata.json").write_text("{invalid json\n")

            with self.assertRaisesRegex(
                StageExecutionError,
                "Malformed RLFixer compatibility bundle metadata",
            ):
                run_rlfixer_stage(
                    workspace_root=workspace_root,
                    diagnostics_path=diagnostics_path,
                    inference_dir=inference_dir,
                    compatibility_bundle_root=bundle_root,
                    stage_output_dir=temp_root / "arodnap-out" / "stages" / "rlfixer",
                )

    def _make_inputs(self, root: Path, *, bundle_root: Path | None = None) -> tuple[Path, Path, Path, Path]:
        workspace_root = root / "workspace"
        workspace_root.mkdir()
        diagnostics_path = root / "diagnostics.txt"
        diagnostics_path.write_text("src/main/java/com/example/App.java:10: warning: leak\n")
        inference_dir = root / "inference"
        inference_dir.mkdir()

        bundle_root = bundle_root or (root / "arodnap-out" / "stages" / "rlfixer" / "compat_bundle")
        info_dir = bundle_root / "info"
        jar_dir = bundle_root / "jarfile"
        info_dir.mkdir(parents=True)
        jar_dir.mkdir(parents=True)
        (info_dir / "classes").write_text("com.example.App\n")
        (info_dir / "sources").write_text("src/main/java/com/example/App.java\n")
        (bundle_root / "metadata.json").write_text("{}\n")
        (jar_dir / "workspace.jar").write_bytes(b"fake-jar")

        return workspace_root, diagnostics_path, inference_dir, bundle_root


if __name__ == "__main__":
    unittest.main()
