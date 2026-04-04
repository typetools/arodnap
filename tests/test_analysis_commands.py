from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from arodnap.cli.main import main
from arodnap.contracts import ReanalyzeResult
from arodnap.orchestrator.results import OutputLayout
from tests.fixture_helpers import copy_fixture, workspace_text_snapshot


class AnalysisCommandsTest(unittest.TestCase):
    def test_analyze_uses_workspace_copy_and_emits_diagnostics_and_metadata_only(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            repo_root = copy_fixture("gradle-pipeline-baseline", temp_root)
            out_dir = temp_root / "arodnap-out"
            before_snapshot = workspace_text_snapshot(repo_root)

            with patch("arodnap.orchestrator.pipeline.analyze_once", side_effect=self._fake_analyze(repo_root)):
                with patch(
                    "arodnap.orchestrator.pipeline.reanalyze",
                    side_effect=AssertionError("analyze should not call reanalyze"),
                ):
                    self.assertEqual(main(["analyze", "--out-dir", str(out_dir), str(repo_root)]), 0)

            self.assertEqual(workspace_text_snapshot(repo_root), before_snapshot)
            self._assert_analysis_outputs(
                layout=OutputLayout.from_root(out_dir),
                expected_warning_count=1,
                expect_inference_files=False,
            )

    def test_infer_uses_workspace_copy_and_emits_inference_without_running_stages(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            repo_root = copy_fixture("gradle-pipeline-baseline", temp_root)
            out_dir = temp_root / "arodnap-out"
            before_snapshot = workspace_text_snapshot(repo_root)

            with patch("arodnap.orchestrator.pipeline.reanalyze", side_effect=self._fake_infer(repo_root)):
                with patch(
                    "arodnap.stages.registry.run_close_injector_stage",
                    side_effect=AssertionError("infer should not run close injector"),
                ):
                    self.assertEqual(main(["infer", "--out-dir", str(out_dir), str(repo_root)]), 0)

            self.assertEqual(workspace_text_snapshot(repo_root), before_snapshot)
            self._assert_analysis_outputs(
                layout=OutputLayout.from_root(out_dir),
                expected_warning_count=2,
                expect_inference_files=True,
            )

    def _assert_analysis_outputs(
        self,
        *,
        layout: OutputLayout,
        expected_warning_count: int,
        expect_inference_files: bool,
    ) -> None:
        self.assertTrue(layout.report_path.is_file())
        self.assertTrue(layout.manifest_path.is_file())
        self.assertTrue(layout.diagnostics_dir.is_dir())
        self.assertTrue(layout.inference_dir.is_dir())
        self.assertTrue(layout.logs_dir.is_dir())
        self.assertTrue(layout.stages_dir.is_dir())
        self.assertFalse(layout.patches_manifest_path.exists())
        self.assertEqual(list(layout.stages_dir.iterdir()), [])

        manifest = json.loads(layout.manifest_path.read_text())
        report = json.loads(layout.report_path.read_text())

        self.assertTrue(manifest["success"])
        self.assertIsNone(manifest["final_patch_manifest"])
        self.assertEqual(manifest["stage_history"], [])
        self.assertFalse(manifest["legacy_regression_enabled"])
        self.assertEqual(manifest["config"]["workspace_mode"], "copy")
        self.assertFalse(Path(manifest["workspace_root"]).exists())

        self.assertTrue(report["success"])
        self.assertEqual(report["diagnostics"]["final_warning_count"], expected_warning_count)
        self.assertEqual(report["executed_stages"], [])
        self.assertIsNone(report["artifacts"]["patches_manifest"])
        self.assertEqual(report["artifacts"]["patch_bundle_dir"], str(layout.patches_dir))
        self.assertEqual(report["final_analysis"]["label"], "initial")
        self.assertTrue(Path(report["final_analysis"]["adapter_metadata_path"]).is_file())
        self.assertTrue(Path(report["final_analysis"]["diagnostics_path"]).is_file())
        self.assertEqual(report["run_metadata"]["command"], "analyze" if not expect_inference_files else "infer")
        self.assertEqual(report["adapter"]["adapter_name"], "gradle-v1")
        self.assertEqual(report["adapter"]["selected_build_tool"], ["gradle"])
        self.assertEqual(len(report["analysis_runs"]), 1)
        self.assertEqual(report["analysis_runs"][0]["label"], "initial")
        self.assertEqual(report["stage_timings"], [])
        self.assertEqual(report["stage_execution_summary"]["executed"], 0)
        inference_dir = Path(report["final_analysis"]["inference_dir"])
        self.assertTrue(inference_dir.is_dir())
        if expect_inference_files:
            self.assertNotEqual(list(inference_dir.iterdir()), [])
        else:
            self.assertEqual(list(inference_dir.iterdir()), [])

    def _fake_analyze(self, repo_root: Path):
        repo_root = repo_root.resolve()

        def runner(config, *, workspace_root, label, artifacts_root):
            workspace_root = Path(workspace_root).resolve()
            if workspace_root == repo_root:
                raise AssertionError("analyze should inspect a copied workspace, not the original repo")
            return self._write_analysis_result(
                workspace_root=workspace_root,
                label=label,
                artifacts_root=Path(artifacts_root),
                warning_count=1,
                inference_contents=None,
                wpi_log="SKIPPED: analyze does not run WPI.\n",
            )

        return runner

    def _fake_infer(self, repo_root: Path):
        repo_root = repo_root.resolve()

        def runner(config, *, workspace_root, label, artifacts_root):
            workspace_root = Path(workspace_root).resolve()
            if workspace_root == repo_root:
                raise AssertionError("infer should inspect a copied workspace, not the original repo")
            return self._write_analysis_result(
                workspace_root=workspace_root,
                label=label,
                artifacts_root=Path(artifacts_root),
                warning_count=2,
                inference_contents={"initial.ajava": "// inferred\n"},
                wpi_log="wpi ok\n",
            )

        return runner

    def _write_analysis_result(
        self,
        *,
        workspace_root: Path,
        label: str,
        artifacts_root: Path,
        warning_count: int,
        inference_contents: dict[str, str] | None,
        wpi_log: str,
    ) -> ReanalyzeResult:
        layout = OutputLayout.from_root(artifacts_root)
        analysis_paths = layout.analysis_paths(label)
        analysis_paths.ensure()

        source_root = workspace_root / "src" / "main" / "java"
        source_files = sorted(source_root.rglob("*.java"))
        compiled_outputs_root = workspace_root / "build" / "classes" / "java" / "main"
        compiled_outputs_root.mkdir(parents=True, exist_ok=True)

        class_names: list[str] = []
        for source_file in source_files:
            relative = source_file.relative_to(source_root)
            if relative.name == "module-info.java":
                continue
            class_names.append(relative.with_suffix("").as_posix().replace("/", "."))
            class_output = compiled_outputs_root / relative.with_suffix(".class")
            class_output.parent.mkdir(parents=True, exist_ok=True)
            class_output.write_bytes(relative.as_posix().encode("utf-8"))

        analysis_paths.source_files_file.write_text(
            "\n".join(str(path.resolve()) for path in source_files) + "\n"
        )
        analysis_paths.app_classes_file.write_text("\n".join(class_names) + "\n")
        analysis_paths.classpath_entries_file.write_text(str(compiled_outputs_root.resolve()) + "\n")
        analysis_paths.adapter_metadata_path.write_text(
            json.dumps(
                {
                    "adapter_name": "gradle-v1",
                    "build_system": "gradle",
                    "build_tool": ["gradle"],
                    "build_tool_source": "system",
                    "classpath_entries_file": str(analysis_paths.classpath_entries_file.resolve()),
                    "compile_target": "classes",
                    "compiled_classes_root": str(compiled_outputs_root.resolve()),
                    "source_root": str(source_root.resolve()),
                },
                indent=2,
                sort_keys=True,
            )
            + "\n"
        )
        analysis_paths.wpi_log_path.write_text(wpi_log)
        analysis_paths.inference_dir.mkdir(parents=True, exist_ok=True)
        if inference_contents is not None:
            for filename, contents in inference_contents.items():
                (analysis_paths.inference_dir / filename).write_text(contents)
        analysis_paths.diagnostics_path.write_text(
            "\n".join(f"warning {index}" for index in range(warning_count)) + "\n"
        )

        return ReanalyzeResult(
            workspace_root=workspace_root,
            label=label,
            wpi_log_path=analysis_paths.wpi_log_path.resolve(),
            inference_dir=analysis_paths.inference_dir.resolve(),
            diagnostics_path=analysis_paths.diagnostics_path.resolve(),
            warning_count=warning_count,
            source_files_file=analysis_paths.source_files_file.resolve(),
            app_classes_file=analysis_paths.app_classes_file.resolve(),
            classpath_entries_file=analysis_paths.classpath_entries_file.resolve(),
            adapter_metadata_path=analysis_paths.adapter_metadata_path.resolve(),
        )


if __name__ == "__main__":
    unittest.main()
