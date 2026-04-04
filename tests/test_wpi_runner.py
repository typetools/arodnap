import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from arodnap.analysis import WpiRunError, run_wpi
from arodnap.contracts import RunConfig, Timeouts
from arodnap.runtime import CommandResult


class WpiRunnerTest(unittest.TestCase):
    def test_runner_preserves_reported_inference_directory_even_when_outside_workspace(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            workspace_root = temp_root / "workspace"
            workspace_root.mkdir()
            generated_inference_dir = temp_root / "wpi-ajava-123"
            generated_inference_dir.mkdir()
            log_path = temp_root / "logs" / "wpi.log"
            inference_root = temp_root / "inference" / "initial"
            config = self._make_config(temp_root)

            completed = CommandResult(
                command=(),
                cwd=workspace_root.resolve(),
                returncode=0,
                stdout=f"Directory for generated annotation files: {generated_inference_dir}\n",
                stderr="",
            )

            with patch("arodnap.analysis.wpi_runner._resolve_dljc_python3", return_value=Path("/usr/bin/python3")):
                with patch("arodnap.analysis.wpi_runner.run_command", return_value=completed):
                    result = run_wpi(
                        config,
                        workspace_root=workspace_root,
                        log_path=log_path,
                        inference_root=inference_root,
                    )

            self.assertEqual(result.inference_dir, inference_root.resolve())
            self.assertTrue(result.inference_dir.is_dir())

    def test_runner_raises_when_no_distutils_capable_python3_is_available(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            workspace_root = temp_root / "workspace"
            workspace_root.mkdir()
            log_path = temp_root / "logs" / "wpi.log"
            inference_root = temp_root / "inference" / "initial"
            config = self._make_config(temp_root)

            with patch("arodnap.analysis.wpi_runner._resolve_dljc_python3", return_value=None):
                with self.assertRaisesRegex(WpiRunError, "requires a python3 interpreter with distutils"):
                    run_wpi(
                        config,
                        workspace_root=workspace_root,
                        log_path=log_path,
                        inference_root=inference_root,
                    )

    def test_runner_prepends_python3_shim_for_checker_framework_dljc(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            workspace_root = temp_root / "workspace"
            generated_inference_dir = workspace_root / "build" / "whole-program-inference"
            generated_inference_dir.mkdir(parents=True)
            (generated_inference_dir / "inference.jaif").write_text("annotated\n")
            log_path = temp_root / "logs" / "wpi.log"
            inference_root = temp_root / "inference" / "initial"
            config = self._make_config(temp_root)

            completed = CommandResult(
                command=(),
                cwd=workspace_root.resolve(),
                returncode=0,
                stdout="Starting wpi.sh.\n",
                stderr="",
            )

            with patch("arodnap.analysis.wpi_runner._resolve_dljc_python3", return_value=Path("/usr/bin/python3")):
                with patch(
                    "arodnap.analysis.wpi_runner.run_command",
                    side_effect=self._make_run_command_side_effect(completed),
                ) as run_mock:
                    result = run_wpi(
                        config,
                        workspace_root=workspace_root,
                        log_path=log_path,
                        inference_root=inference_root,
                    )

            path_entries = run_mock.call_args.kwargs["env"]["PATH"].split(os.pathsep)
            shim_dir = Path(path_entries[0])
            self.assertTrue(shim_dir.name.startswith("arodnap-wpi-python-"))
            self.assertFalse(shim_dir.exists())
            self.assertEqual(result.inference_dir, inference_root.resolve())

    def test_runner_marks_wpi_support_scripts_executable(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            cf_root = temp_root / "cf"
            wpi_script = cf_root / "checker" / "bin" / "wpi.sh"
            dljc = cf_root / "checker" / "bin" / ".do-like-javac" / "dljc"
            wpi_script.parent.mkdir(parents=True, exist_ok=True)
            dljc.parent.mkdir(parents=True, exist_ok=True)
            wpi_script.write_text("#!/usr/bin/env bash\n")
            dljc.write_text("#!/usr/bin/env python3\n")
            wpi_script.chmod(0o644)
            dljc.chmod(0o644)

            workspace_root = temp_root / "workspace"
            generated_inference_dir = workspace_root / "build" / "whole-program-inference"
            generated_inference_dir.mkdir(parents=True)
            (generated_inference_dir / "inference.jaif").write_text("annotated\n")
            log_path = temp_root / "logs" / "wpi.log"
            inference_root = temp_root / "inference" / "initial"
            config = self._make_config(temp_root, cf_root=cf_root)
            completed = CommandResult(
                command=(),
                cwd=workspace_root.resolve(),
                returncode=0,
                stdout="ok\n",
                stderr="",
            )

            with patch("arodnap.analysis.wpi_runner._resolve_dljc_python3", return_value=Path("/usr/bin/python3")):
                with patch("arodnap.analysis.wpi_runner.run_command", return_value=completed):
                    run_wpi(
                        config,
                        workspace_root=workspace_root,
                        log_path=log_path,
                        inference_root=inference_root,
                    )

            self.assertTrue(wpi_script.stat().st_mode & 0o111)
            self.assertTrue(dljc.stat().st_mode & 0o111)

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

            completed = CommandResult(
                command=(),
                cwd=workspace_root.resolve(),
                returncode=0,
                stdout="Starting wpi.sh.\n",
                stderr="",
            )
            with patch("arodnap.analysis.wpi_runner._resolve_dljc_python3", return_value=Path("/usr/bin/python3")):
                with patch(
                    "arodnap.analysis.wpi_runner.run_command",
                    side_effect=self._make_run_command_side_effect(completed),
                ) as run_mock:
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
                self.assertEqual(command[0], "bash")
                self.assertEqual(
                    command[1],
                    str((config.cf_root / "checker" / "bin" / "wpi.sh").resolve()),
                )
                self.assertEqual(command[2:4], ["-d", str(workspace_root.resolve())])
                self.assertIn("-b", command)
                self.assertIn("--info -x=test", command)
                self.assertIn("-c", command)
                self.assertIn("classes", command)
                self.assertIn("--", command)
                self.assertIn("--checker", command)
                self.assertIn("resourceleak", command)
                self.assertNotIn("/helpers/wpi.sh", " ".join(command))
                self.assertNotIn("src/lib/info", " ".join(command))

                kwargs = run_mock.call_args.kwargs
                self.assertEqual(kwargs["cwd"], workspace_root.resolve())
                self.assertEqual(kwargs["env"]["CHECKERFRAMEWORK"], str(config.cf_root))
                log_text = result.log_path.read_text()
                self.assertIn("TOOL: wpi", log_text)
                self.assertIn(f"CWD: {workspace_root.resolve()}", log_text)
                self.assertIn(f"TIMEOUT_SECONDS: {config.timeouts.analysis_seconds}", log_text)
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
            completed = CommandResult(
                command=(),
                cwd=workspace_root.resolve(),
                returncode=1,
                stdout="",
                stderr="boom\n",
            )

            with patch("arodnap.analysis.wpi_runner._resolve_dljc_python3", return_value=Path("/usr/bin/python3")):
                with patch("arodnap.analysis.wpi_runner.run_command", return_value=completed):
                    with self.assertRaisesRegex(WpiRunError, "WPI failed"):
                        run_wpi(
                            config,
                            workspace_root=workspace_root,
                            log_path=log_path,
                            inference_root=temp_root / "inference" / "failed",
                        )
                    self.assertTrue(log_path.is_file())
                    self.assertIn("boom", log_path.read_text())

    def test_runner_raises_when_inference_output_directory_cannot_be_located(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            workspace_root = temp_root / "workspace"
            workspace_root.mkdir()
            log_path = temp_root / "logs" / "wpi.log"
            config = self._make_config(temp_root)
            completed = CommandResult(
                command=(),
                cwd=workspace_root.resolve(),
                returncode=0,
                stdout="ok\n",
                stderr="",
            )

            with patch("arodnap.analysis.wpi_runner._resolve_dljc_python3", return_value=Path("/usr/bin/python3")):
                with patch("arodnap.analysis.wpi_runner.run_command", return_value=completed):
                    with self.assertRaisesRegex(WpiRunError, "no inferred output directory"):
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
        cf_root: Path | None = None,
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
            cf_root=(cf_root or Path("/Users/sanjay/projects/arodnap/checker_framework/checker-framework-3.49.0")),
            close_injector_jar=root / "close.jar",
            owning_field_jar=root / "owning.jar",
            rlpatcher_jar=root / "rlpatcher.jar",
            timeouts=Timeouts(build_seconds=1, analysis_seconds=2, stage_seconds=3),
        )

    def _make_run_command_side_effect(self, template: CommandResult):
        def fake_run(command: list[str], **kwargs) -> CommandResult:
            return CommandResult(
                command=tuple(command),
                cwd=kwargs.get("cwd"),
                returncode=template.returncode,
                stdout=template.stdout,
                stderr=template.stderr,
            )

        return fake_run


if __name__ == "__main__":
    unittest.main()
