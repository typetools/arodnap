import json
import tempfile
import unittest
from pathlib import Path

from arodnap.contracts import PipelineState, ReanalyzeResult, RunConfig, StageResult, Timeouts
from arodnap.orchestrator.results import OutputLayout, write_report, write_run_manifest


class ResultsTest(unittest.TestCase):
    def test_output_layout_promotes_stage_patch_manifest_and_writes_top_level_reports(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            layout = OutputLayout.from_root(temp_root / "arodnap-out")
            layout.ensure()
            stage_dir = layout.stage_dir("rlpatcher")
            stage_dir.mkdir(parents=True, exist_ok=True)
            stage_manifest_path = stage_dir / "patch_manifest.json"
            stage_manifest_payload = {
                "patches": [
                    {
                        "changed_files": ["src/main/java/App.java"],
                        "patch_file": str((stage_dir / "patches" / "app.patch").resolve()),
                        "preimage_hashes": {"src/main/java/App.java": "abc"},
                        "stage": "rlpatcher",
                        "strip_level": 0,
                        "target_root": ".",
                    }
                ],
                "stage": "rlpatcher",
            }
            stage_manifest_path.write_text(json.dumps(stage_manifest_payload, indent=2, sort_keys=True) + "\n")

            state = self._make_state(layout)
            state.final_patch_manifest = layout.promote_patch_manifest(stage_manifest_path)

            write_run_manifest(layout, state, success=True)
            write_report(layout, state, success=True)

            self.assertEqual(json.loads(layout.patches_manifest_path.read_text()), stage_manifest_payload)

            manifest = json.loads(layout.manifest_path.read_text())
            self.assertTrue(manifest["success"])
            self.assertEqual(manifest["final_patch_manifest"], str(layout.patches_manifest_path))
            self.assertEqual(manifest["adapter_name"], "gradle-v1")

            report = json.loads(layout.report_path.read_text())
            self.assertTrue(report["success"])
            self.assertEqual(report["artifacts"]["patches_manifest"], str(layout.patches_manifest_path))
            self.assertEqual(report["diagnostics"]["final_warning_count"], 2)
            self.assertEqual(report["executed_stages"][0]["stage"], "close_injector")

    def _make_state(self, layout: OutputLayout) -> PipelineState:
        config = RunConfig(
            command="repair",
            repo_root=Path("/repo"),
            out_dir=layout.root,
            keep_workspace=False,
            workspace_mode="copy",
            build_args=[],
            compile_target="classes",
            patch_dir=None,
            cf_root=Path("/cf"),
            close_injector_jar=Path("/close.jar"),
            owning_field_jar=Path("/owning.jar"),
            rlpatcher_jar=Path("/rlpatcher.jar"),
            timeouts=Timeouts(build_seconds=1, analysis_seconds=2, stage_seconds=3),
        )
        analysis = ReanalyzeResult(
            workspace_root=Path("/workspace"),
            label="initial",
            wpi_log_path=layout.logs_dir / "initial" / "wpi.log",
            inference_dir=layout.inference_dir / "initial",
            diagnostics_path=layout.diagnostics_dir / "initial.txt",
            warning_count=2,
            source_files_file=layout.logs_dir / "initial" / "source-files.txt",
            app_classes_file=layout.logs_dir / "initial" / "app-classes.txt",
            classpath_entries_file=layout.logs_dir / "initial" / "classpath-entries.txt",
            adapter_metadata_path=layout.logs_dir / "initial" / "adapter-metadata.json",
        )
        return PipelineState(
            config=config,
            workspace_root=Path("/workspace"),
            build_system="gradle",
            adapter_name="gradle-v1",
            current_analysis=analysis,
            stage_history=[
                StageResult(
                    stage="close_injector",
                    changed=True,
                    changed_files=["src/main/java/App.java"],
                    rerun_required=True,
                    artifacts={"log": str(layout.stage_dir("close_injector") / "stage.log")},
                    notes=["patched"],
                    success=True,
                )
            ],
            artifacts_root=layout.root,
            final_patch_manifest=None,
            legacy_regression_enabled=False,
        )


if __name__ == "__main__":
    unittest.main()
