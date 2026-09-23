import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from arodnap.analysis import RlcRunError, count_warnings, run_resource_leak_checker
from arodnap.contracts import RunConfig, Timeouts
from arodnap.runtime import CommandResult, Jdk


class RlcRunnerTest(unittest.TestCase):
    def setUp(self) -> None:
        # Run the checker on a fixed JDK so the command does not depend on this machine.
        jdk_patcher = patch(
            "arodnap.analysis.rlc_runner.resolve_analysis_jdk",
            return_value=Jdk(home=Path("/jdk-21"), major_version=21, source="PATH"),
        )
        jdk_patcher.start()
        self.addCleanup(jdk_patcher.stop)

    def test_warning_count_is_deterministic(self) -> None:
        diagnostics_text = "\n".join(
            [
                "src/A.java:10: warning: [required.method.not.called] first",
                "src/B.java:20: warning: [required.method.not.called] second",
                "2 warnings",
            ]
        )
        self.assertEqual(count_warnings(diagnostics_text), 2)

    def test_success_writes_diagnostics_and_counts_warnings(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            config = self._make_config(temp_root)
            workspace_root, source_files_file, classpath_entries_file, inference_dir = self._make_inputs(temp_root)
            diagnostics_path = temp_root / "diagnostics.txt"
            completed = CommandResult(
                command=(),
                cwd=workspace_root.resolve(),
                returncode=0,
                stdout="",
                stderr="\n".join(
                    [
                        "src/A.java:10: warning: [required.method.not.called] first",
                        "src/B.java:20: warning: [required.method.not.called] second",
                    ]
                ),
            )

            with patch(
                "arodnap.analysis.rlc_runner.run_command",
                side_effect=self._make_run_command_side_effect(completed),
            ) as run_mock:
                result = run_resource_leak_checker(
                    config,
                    workspace_root=workspace_root,
                    source_files_file=source_files_file,
                    classpath_entries_file=classpath_entries_file,
                    inference_dir=inference_dir,
                    diagnostics_path=diagnostics_path,
                )
                self.assertEqual(result.warning_count, 2)
                self.assertEqual(result.diagnostics_path, diagnostics_path.resolve())
                self.assertTrue(result.diagnostics_path.is_file())
                command = run_mock.call_args.args[0]
                self.assertEqual(
                    command[:3],
                    ["/jdk-21/bin/java", "-jar", str(config.cf_root / "checker" / "dist" / "checker.jar")],
                )
                self.assertIn("-processor", command)
                self.assertIn("org.checkerframework.checker.resourceleak.ResourceLeakChecker", command)
                self.assertIn(f"-Aajava={inference_dir.resolve()}", command)
                self.assertIn(f"@{source_files_file.resolve()}", command)

    def test_non_wpi_analysis_can_run_without_inference_dir(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            config = self._make_config(temp_root)
            workspace_root, source_files_file, classpath_entries_file, _ = self._make_inputs(temp_root)
            diagnostics_path = temp_root / "diagnostics.txt"
            completed = CommandResult(
                command=(),
                cwd=workspace_root.resolve(),
                returncode=0,
                stdout="",
                stderr="src/A.java:10: warning: [required.method.not.called] first",
            )

            with patch(
                "arodnap.analysis.rlc_runner.run_command",
                side_effect=self._make_run_command_side_effect(completed),
            ) as run_mock:
                result = run_resource_leak_checker(
                    config,
                    workspace_root=workspace_root,
                    source_files_file=source_files_file,
                    classpath_entries_file=classpath_entries_file,
                    inference_dir=None,
                    diagnostics_path=diagnostics_path,
                )

            self.assertEqual(result.warning_count, 1)
            command = run_mock.call_args.args[0]
            self.assertNotIn("-Aajava=None", command)
            self.assertFalse(any(str(item).startswith("-Aajava=") for item in command))

    def test_missing_artifacts_fail_clearly(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            config = self._make_config(temp_root)
            workspace_root = temp_root / "workspace"
            workspace_root.mkdir()

            with self.assertRaisesRegex(RlcRunError, "source files file"):
                run_resource_leak_checker(
                    config,
                    workspace_root=workspace_root,
                    source_files_file=temp_root / "missing-sources.txt",
                    classpath_entries_file=temp_root / "classpath.txt",
                    inference_dir=temp_root / "inference",
                    diagnostics_path=temp_root / "diagnostics.txt",
                )

    def test_subprocess_failure_raises_clear_error(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            config = self._make_config(temp_root)
            workspace_root, source_files_file, classpath_entries_file, inference_dir = self._make_inputs(temp_root)
            completed = CommandResult(
                command=(),
                cwd=workspace_root.resolve(),
                returncode=1,
                stdout="",
                stderr="boom",
            )

            with patch("arodnap.analysis.rlc_runner.run_command", return_value=completed):
                with self.assertRaisesRegex(RlcRunError, "RLC failed"):
                    run_resource_leak_checker(
                        config,
                        workspace_root=workspace_root,
                        source_files_file=source_files_file,
                        classpath_entries_file=classpath_entries_file,
                        inference_dir=inference_dir,
                        diagnostics_path=temp_root / "diagnostics.txt",
                    )

    def _make_inputs(self, root: Path) -> tuple[Path, Path, Path, Path]:
        workspace_root = root / "workspace"
        workspace_root.mkdir()
        source_files_file = root / "sources.txt"
        source_files_file.write_text("/tmp/Example.java\n")
        classpath_entries_file = root / "classpath.txt"
        classpath_entries_file.write_text("/tmp/classes\n/tmp/dependency.jar\n")
        inference_dir = root / "inference"
        inference_dir.mkdir()
        return workspace_root, source_files_file, classpath_entries_file, inference_dir

    def _make_config(self, root: Path) -> RunConfig:
        return RunConfig(
            command="infer",
            repo_root=(root / "repo").resolve(),
            out_dir=(root / "out").resolve(),
            keep_workspace=False,
            workspace_mode="copy",
            build_args=[],
            compile_target="classes",
            patch_dir=None,
            cf_root=Path("/Users/sanjay/projects/arodnap/checker_framework/checker-framework-3.49.0"),
            close_injector_jar=root / "close.jar",
            owning_field_jar=root / "owning.jar",
            rlfixer_jar=root / "rlfixer.jar",
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
