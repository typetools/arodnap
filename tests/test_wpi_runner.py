import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import subprocess

from arodnap.analysis import WpiRunError, run_wpi
from arodnap.contracts import RunConfig, Timeouts


class WpiRunnerTest(unittest.TestCase):
    def test_runner_builds_expected_command_and_preserves_inference(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            workspace_root = temp_root / "workspace"
            generated_inference_dir = workspace_root / "build" / "whole-program-inference"
            generated_inference_dir.mkdir(parents=True)
            (generated_inference_dir / "inference.jaif").write_text("annotated\n")
            log_path = temp_root / "logs" / "wpi.log"
            inference_root = temp_root / "inference" / "initial"
            config = self._make_config(temp_root, build_args=["--info", "-x=test"], compile_target="classes")

            completed = subprocess.CompletedProcess(
                args=[],
                returncode=0,
                stdout="Starting wpi.sh.\n",
                stderr="",
            )
            with patch("subprocess.run", return_value=completed) as run_mock:
                result = run_wpi(
                    config,
                    workspace_root=workspace_root,
                    log_path=log_path,
                    inference_root=inference_root,
                )
                self.assertEqual(result.log_path, log_path.resolve())
                self.assertEqual(result.inference_dir, inference_root.resolve())
                self.assertTrue(result.log_path.is_file())
                self.assertTrue(result.inference_dir.is_dir())
                self.assertEqual((result.inference_dir / "inference.jaif").read_text(), "annotated\n")

                command = run_mock.call_args.args[0]
                self.assertEqual(
                    command[0],
                    str((config.cf_root / "checker" / "bin" / "wpi.sh").resolve()),
                )
                self.assertEqual(command[1:3], ["-d", str(workspace_root.resolve())])
                self.assertIn("-b", command)
                self.assertIn("--info -x=test", command)
                self.assertIn("-c", command)
                self.assertIn("classes", command)
                self.assertNotIn("/helpers/wpi.sh", " ".join(command))
                self.assertNotIn("src/lib/info", " ".join(command))

                kwargs = run_mock.call_args.kwargs
                self.assertEqual(kwargs["cwd"], workspace_root.resolve())
                self.assertEqual(kwargs["env"]["CHECKERFRAMEWORK"], str(config.cf_root))
                log_text = result.log_path.read_text()
                self.assertIn("COMMAND:", log_text)
                self.assertIn("EXIT_CODE: 0", log_text)
                self.assertIn("Starting wpi.sh.", log_text)

    def test_runner_raises_on_subprocess_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            workspace_root = temp_root / "workspace"
            workspace_root.mkdir()
            log_path = temp_root / "logs" / "wpi.log"
            config = self._make_config(temp_root)
            completed = subprocess.CompletedProcess(
                args=[],
                returncode=1,
                stdout="",
                stderr="boom\n",
            )

            with patch("subprocess.run", return_value=completed):
                with self.assertRaisesRegex(WpiRunError, "WPI failed"):
                    run_wpi(
                        config,
                        workspace_root=workspace_root,
                        log_path=log_path,
                        inference_root=temp_root / "inference" / "failed",
                    )
                self.assertTrue(log_path.is_file())
                self.assertIn("boom", log_path.read_text())

    def test_runner_raises_when_inference_output_is_missing(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            workspace_root = temp_root / "workspace"
            workspace_root.mkdir()
            log_path = temp_root / "logs" / "wpi.log"
            config = self._make_config(temp_root)
            completed = subprocess.CompletedProcess(
                args=[],
                returncode=0,
                stdout="ok\n",
                stderr="",
            )

            with patch("subprocess.run", return_value=completed):
                with self.assertRaisesRegex(WpiRunError, "no inferred output"):
                    run_wpi(
                        config,
                        workspace_root=workspace_root,
                        log_path=log_path,
                        inference_root=temp_root / "inference" / "missing",
                    )

    def _make_config(
        self,
        root: Path,
        *,
        build_args: list[str] | None = None,
        compile_target: str | None = None,
    ) -> RunConfig:
        return RunConfig(
            command="infer",
            repo_root=(root / "repo").resolve(),
            out_dir=(root / "out").resolve(),
            keep_workspace=False,
            workspace_mode="copy",
            build_args=build_args or [],
            compile_target=compile_target,
            patch_dir=None,
            cf_root=Path("/Users/sanjay/projects/arodnap/checker_framework/checker-framework-3.49.0"),
            close_injector_jar=root / "close.jar",
            owning_field_jar=root / "owning.jar",
            rlpatcher_jar=root / "rlpatcher.jar",
            timeouts=Timeouts(build_seconds=1, analysis_seconds=2, stage_seconds=3),
        )


if __name__ == "__main__":
    unittest.main()
