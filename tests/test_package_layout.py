import importlib
import tempfile
import unittest
from pathlib import Path

from arodnap.orchestrator.results import OutputLayout, ReanalyzeResult, StageResult
from arodnap.orchestrator.state import PipelineState


class PackageLayoutTest(unittest.TestCase):
    def test_plan_required_modules_import(self) -> None:
        module_names = [
            "arodnap.cli.main",
            "arodnap.cli.commands.analyze",
            "arodnap.cli.commands.infer",
            "arodnap.cli.commands.repair",
            "arodnap.cli.commands.apply",
            "arodnap.orchestrator.state",
            "arodnap.orchestrator.results",
            "arodnap.stages.base",
            "arodnap.stages.close_injector",
            "arodnap.stages.owning_field",
            "arodnap.stages.rlfixer",
            "arodnap.stages.rlpatcher",
            "arodnap.compat.rlfixer_inputs",
        ]

        for module_name in module_names:
            with self.subTest(module=module_name):
                self.assertIsNotNone(importlib.import_module(module_name))

    def test_plan_required_imports_reexport_contract_types(self) -> None:
        self.assertIs(PipelineState, importlib.import_module("arodnap.orchestrator.state").PipelineState)
        self.assertIs(ReanalyzeResult, importlib.import_module("arodnap.orchestrator.results").ReanalyzeResult)
        self.assertIs(StageResult, importlib.import_module("arodnap.orchestrator.results").StageResult)

    def test_output_layout_routes_analysis_artifacts_under_layout_dirs(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            layout = OutputLayout.from_root(Path(temp_dir) / "arodnap-out")
            layout.ensure()
            analysis_paths = layout.analysis_paths("initial")
            analysis_paths.ensure()

            self.assertEqual(analysis_paths.wpi_log_path, layout.logs_dir / "initial" / "wpi.log")
            self.assertEqual(analysis_paths.inference_dir, layout.inference_dir / "initial")
            self.assertEqual(analysis_paths.diagnostics_path, layout.diagnostics_dir / "initial.txt")
            self.assertEqual(analysis_paths.source_files_file, layout.logs_dir / "initial" / "source-files.txt")
            self.assertTrue(layout.logs_dir.is_dir())
            self.assertTrue(layout.inference_dir.is_dir())
            self.assertTrue(layout.diagnostics_dir.is_dir())


if __name__ == "__main__":
    unittest.main()
