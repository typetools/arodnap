import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import subprocess

from arodnap.contracts import RunConfig, StageResult, Timeouts
from arodnap.stages.close_injector import StageExecutionError, run_close_injector_stage


class CloseInjectorStageTest(unittest.TestCase):
    def setUp(self) -> None:
        # Keep the command shape independent of the JDK installed on this machine.
        java_patcher = patch("arodnap.stages.close_injector.java_executable", return_value="java")
        java_patcher.start()
        self.addCleanup(java_patcher.stop)

    def test_changed_run_normalizes_patch_and_writes_stage_result(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            workspace_root, diagnostics_path = self._make_workspace(temp_root)
            stage_output_dir = temp_root / "arodnap-out" / "stages" / "close_injector"
            raw_patch_path = stage_output_dir / "java-parser-AutoCloseInjector.patch"
            source_file = workspace_root / "src" / "main" / "java" / "com" / "example" / "Demo.java"

            def fake_run(command, **kwargs):
                program = Path(command[0]).name
                if program == "java":
                    raw_patch_path.parent.mkdir(parents=True, exist_ok=True)
                    raw_patch_path.write_text(
                        "\n".join(
                            [
                                f"--- {source_file}",
                                f"+++ {source_file}",
                                "@@ -1 +1,2 @@",
                                " class Demo {}",
                                "+// inserted close method",
                                "",
                            ]
                        )
                    )
                    return subprocess.CompletedProcess(command, 0, stdout="patched\n", stderr="")
                raise AssertionError(f"Unexpected command: {command}")

            with patch("subprocess.run", side_effect=fake_run):
                result = run_close_injector_stage(
                    self._make_config(temp_root),
                    workspace_root=workspace_root,
                    diagnostics_path=diagnostics_path,
                    stage_output_dir=stage_output_dir,
                )

            self.assertEqual(
                result,
                StageResult(
                    stage="close_injector",
                    changed=True,
                    changed_files=["src/main/java/com/example/Demo.java"],
                    rerun_required=True,
                    artifacts={
                        "log": str((stage_output_dir / "stage.log").resolve()),
                        "patch": str((stage_output_dir / "close_injector.patch").resolve()),
                    },
                    notes=["Applied close-injector patch affecting 1 file(s)."],
                    success=True,
                ),
            )
            normalized_patch = (stage_output_dir / "close_injector.patch").read_text()
            self.assertIn("--- src/main/java/com/example/Demo.java", normalized_patch)
            self.assertIn("+++ src/main/java/com/example/Demo.java", normalized_patch)
            self.assertFalse(raw_patch_path.exists())
            log_contents = (stage_output_dir / "stage.log").read_text()
            self.assertIn("PATCH_BINARY: arodnap built-in", log_contents)
            self.assertIn("patching file src/main/java/com/example/Demo.java", log_contents)

            stage_result_payload = json.loads((stage_output_dir / "stage_result.json").read_text())
            self.assertEqual(stage_result_payload, result.to_dict())

    def test_noop_run_writes_stage_result_without_patch_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            workspace_root, diagnostics_path = self._make_workspace(temp_root)
            stage_output_dir = temp_root / "arodnap-out" / "stages" / "close_injector"

            completed = subprocess.CompletedProcess(["java"], 0, stdout="No valid warnings found to process.\n", stderr="")
            with patch("subprocess.run", return_value=completed):
                result = run_close_injector_stage(
                    self._make_config(temp_root),
                    workspace_root=workspace_root,
                    diagnostics_path=diagnostics_path,
                    stage_output_dir=stage_output_dir,
                )

            self.assertFalse(result.changed)
            self.assertFalse(result.rerun_required)
            self.assertEqual(result.changed_files, [])
            self.assertEqual(result.artifacts, {"log": str((stage_output_dir / "stage.log").resolve())})
            self.assertTrue((stage_output_dir / "stage_result.json").is_file())
            self.assertFalse((stage_output_dir / "close_injector.patch").exists())

    def test_invalid_patch_paths_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            workspace_root, diagnostics_path = self._make_workspace(temp_root)
            stage_output_dir = temp_root / "arodnap-out" / "stages" / "close_injector"
            raw_patch_path = stage_output_dir / "java-parser-AutoCloseInjector.patch"

            def fake_run(command, **kwargs):
                if Path(command[0]).name == "java":
                    raw_patch_path.parent.mkdir(parents=True, exist_ok=True)
                    raw_patch_path.write_text(
                        "\n".join(
                            [
                                "--- /tmp/other/Outside.java",
                                "+++ /tmp/other/Outside.java",
                                "@@ -1 +1 @@",
                                "-class Outside {}",
                                "+class Outside { }",
                                "",
                            ]
                        )
                    )
                    return subprocess.CompletedProcess(command, 0, stdout="", stderr="")
                raise AssertionError("Patch command should not run when normalization fails.")

            with patch("subprocess.run", side_effect=fake_run):
                with self.assertRaisesRegex(StageExecutionError, "did not reference a file under workspace root"):
                    run_close_injector_stage(
                        self._make_config(temp_root),
                        workspace_root=workspace_root,
                        diagnostics_path=diagnostics_path,
                        stage_output_dir=stage_output_dir,
                    )

    def _make_workspace(self, root: Path) -> tuple[Path, Path]:
        workspace_root = root / "workspace"
        source_file = workspace_root / "src" / "main" / "java" / "com" / "example" / "Demo.java"
        source_file.parent.mkdir(parents=True)
        source_file.write_text("class Demo {}\n")
        diagnostics_path = root / "diagnostics.txt"
        diagnostics_path.write_text(f"{source_file}:1: warning: leak\n")
        return workspace_root, diagnostics_path

    def _make_config(self, root: Path) -> RunConfig:
        return RunConfig(
            command="repair",
            repo_root=(root / "repo").resolve(),
            out_dir=(root / "out").resolve(),
            keep_workspace=False,
            workspace_mode="copy",
            build_args=[],
            compile_target="classes",
            patch_dir=None,
            cf_root=root / "checker-framework",
            close_injector_jar=root / "AutoCloseInjector.jar",
            owning_field_jar=root / "owning.jar",
            rlfixer_jar=root / "rlfixer.jar",
            rlpatcher_jar=root / "rlpatcher.jar",
            timeouts=Timeouts(),
        )


if __name__ == "__main__":
    unittest.main()
