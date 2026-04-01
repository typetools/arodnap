import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import subprocess

from arodnap.analysis import RlcRunError, count_warnings, run_resource_leak_checker
from arodnap.contracts import RunConfig, Timeouts


class RlcRunnerTest(unittest.TestCase):
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
            completed = subprocess.CompletedProcess(
                args=[],
                returncode=0,
                stdout="",
                stderr="\n".join(
                    [
                        "src/A.java:10: warning: [required.method.not.called] first",
                        "src/B.java:20: warning: [required.method.not.called] second",
                    ]
                ),
            )

            with patch("subprocess.run", return_value=completed) as run_mock:
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
                self.assertIn(str((config.cf_root / "checker" / "bin" / "javac").resolve()), command[0])
                self.assertIn("-processor", command)
                self.assertIn("org.checkerframework.checker.resourceleak.ResourceLeakChecker", command)
                self.assertIn(f"-Aajava={inference_dir.resolve()}", command)
                self.assertIn(f"@{source_files_file.resolve()}", command)

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
            completed = subprocess.CompletedProcess(args=[], returncode=1, stdout="", stderr="boom")

            with patch("subprocess.run", return_value=completed):
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
            rlpatcher_jar=root / "rlpatcher.jar",
            timeouts=Timeouts(build_seconds=1, analysis_seconds=2, stage_seconds=3),
        )


if __name__ == "__main__":
    unittest.main()
