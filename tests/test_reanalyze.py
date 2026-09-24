import tempfile
import unittest
from pathlib import Path
import importlib
from unittest.mock import patch

from arodnap.analysis.reanalyze import ReanalyzeError, reanalyze
from arodnap.analysis.rlc_runner import RlcRunError, RlcRunResult
from arodnap.analysis.wpi_runner import WpiRunResult
from arodnap.contracts import RunConfig, Timeouts
from arodnap.orchestrator.results import OutputLayout
from tests.fixture_helpers import FixtureGradleAdapter


FIXTURES_ROOT = Path(__file__).resolve().parent / "fixtures"
REANALYZE_MODULE = importlib.import_module("arodnap.analysis.reanalyze")


class ReanalyzeTest(unittest.TestCase):
    def test_reanalyze_returns_complete_result_with_existing_paths(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            config = self._make_config(temp_root)
            workspace_root = FIXTURES_ROOT / "gradle-pipeline-baseline"
            artifacts_root = temp_root / "analysis"
            output_layout = OutputLayout.from_root(artifacts_root)
            analysis_paths = output_layout.analysis_paths("initial")

            def fake_run_wpi(config, *, workspace_root, log_path, inference_root, **_kwargs):
                self.assertEqual(log_path, analysis_paths.wpi_log_path)
                self.assertEqual(inference_root, analysis_paths.inference_dir)
                log_path.parent.mkdir(parents=True, exist_ok=True)
                log_path.write_text("wpi ok\n")
                inference_root.mkdir(parents=True, exist_ok=True)
                (inference_root / "Sample.ajava").write_text("inferred\n")
                return WpiRunResult(log_path=log_path.resolve(), inference_dir=inference_root.resolve(), iterations=2)

            def fake_run_rlc(
                config,
                *,
                workspace_root,
                source_files_file,
                classpath_entries_file,
                inference_dir,
                diagnostics_path,
                **_kwargs,
            ):
                self.assertTrue(source_files_file.is_file())
                self.assertTrue(classpath_entries_file.is_file())
                self.assertTrue(inference_dir.is_dir())
                self.assertEqual(source_files_file, analysis_paths.source_files_file)
                self.assertEqual(classpath_entries_file, analysis_paths.classpath_entries_file)
                self.assertEqual(diagnostics_path, analysis_paths.diagnostics_path)
                diagnostics_path.write_text("src/A.java:10: warning: [required.method.not.called] leak\n")
                return RlcRunResult(diagnostics_path=diagnostics_path.resolve(), warning_count=1)

            def fake_select_build_adapter(repo_root, *, compile_target, build_args, build_command=(), **_kwargs):
                return FixtureGradleAdapter(
                    repo_root,
                    compile_target=compile_target,
                    build_args=build_args,
                )

            with patch.object(REANALYZE_MODULE, "select_build_adapter", side_effect=fake_select_build_adapter):
                with patch.object(REANALYZE_MODULE, "run_wpi", side_effect=fake_run_wpi):
                    with patch.object(REANALYZE_MODULE, "run_resource_leak_checker", side_effect=fake_run_rlc):
                        result = reanalyze(
                            config,
                            workspace_root=workspace_root,
                            label="initial",
                            artifacts_root=artifacts_root,
                        )
                        self.assertEqual(result.workspace_root, workspace_root.resolve())
                        self.assertEqual(result.label, "initial")
                        self.assertEqual(result.warning_count, 1)
                        self.assertTrue(result.wpi_log_path.is_file())
                        self.assertTrue(result.inference_dir.is_dir())
                        self.assertTrue(result.diagnostics_path.is_file())
                        self.assertTrue(result.source_files_file.is_file())
                        self.assertTrue(result.app_classes_file.is_file())
                        self.assertTrue(result.classpath_entries_file.is_file())
                        self.assertTrue(result.adapter_metadata_path.is_file())

    def test_reanalyze_wraps_rlc_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            config = self._make_config(temp_root)
            workspace_root = FIXTURES_ROOT / "gradle-pipeline-baseline"
            output_layout = OutputLayout.from_root(temp_root / "analysis")
            analysis_paths = output_layout.analysis_paths("initial")

            def fake_run_wpi(config, *, workspace_root, log_path, inference_root, **_kwargs):
                self.assertEqual(log_path, analysis_paths.wpi_log_path)
                self.assertEqual(inference_root, analysis_paths.inference_dir)
                log_path.parent.mkdir(parents=True, exist_ok=True)
                log_path.write_text("wpi ok\n")
                inference_root.mkdir(parents=True, exist_ok=True)
                return WpiRunResult(log_path=log_path.resolve(), inference_dir=inference_root.resolve(), iterations=2)

            def fake_select_build_adapter(repo_root, *, compile_target, build_args, build_command=(), **_kwargs):
                return FixtureGradleAdapter(
                    repo_root,
                    compile_target=compile_target,
                    build_args=build_args,
                )

            with patch.object(REANALYZE_MODULE, "select_build_adapter", side_effect=fake_select_build_adapter):
                with patch.object(REANALYZE_MODULE, "run_wpi", side_effect=fake_run_wpi):
                    with patch.object(
                        REANALYZE_MODULE,
                        "run_resource_leak_checker",
                        side_effect=RlcRunError("rlc boom"),
                    ):
                        with self.assertRaisesRegex(ReanalyzeError, "rlc boom"):
                            reanalyze(
                                config,
                                workspace_root=workspace_root,
                                label="initial",
                                artifacts_root=temp_root / "analysis",
                            )

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
            timeouts=Timeouts(),
        )


if __name__ == "__main__":
    unittest.main()
