import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from arodnap.patch_tool import PatchTool
from arodnap.runtime import CommandResult
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

            state.current_analysis.adapter_metadata_path.parent.mkdir(parents=True, exist_ok=True)
            state.current_analysis.adapter_metadata_path.write_text(
                json.dumps(
                    {
                        "adapter_name": "gradle-v1",
                        "build_system": "gradle",
                        "build_tool": ["./gradlew"],
                        "build_tool_source": "wrapper",
                        "classpath_entries_file": str(layout.logs_dir / "initial" / "classpath-entries.txt"),
                        "compile_target": "classes",
                        "compiled_classes_root": "/workspace/build/classes/java/main",
                        "source_root": "/workspace/src/main/java",
                    },
                    indent=2,
                    sort_keys=True,
                )
                + "\n"
            )

            run_metadata = {
                "started_at": "2026-04-02T12:00:00Z",
                "completed_at": "2026-04-02T12:00:05Z",
                "elapsed_seconds": 5.0,
            }
            analysis_runs = [
                {
                    "label": "initial",
                    "started_at": "2026-04-02T12:00:00Z",
                    "completed_at": "2026-04-02T12:00:02Z",
                    "elapsed_seconds": 2.0,
                    "success": True,
                    "warning_count": 2,
                }
            ]
            stage_timings = [
                {
                    "stage": "close_injector",
                    "started_at": "2026-04-02T12:00:02Z",
                    "completed_at": "2026-04-02T12:00:03Z",
                    "elapsed_seconds": 1.0,
                    "success": True,
                }
            ]

            with patch(
                "arodnap.orchestrator.results.run_command",
                return_value=CommandResult(
                    command=("java", "-version"),
                    cwd=None,
                    returncode=0,
                    stdout="",
                    stderr='openjdk version "21.0.2"\n',
                ),
            ):
                with patch(
                    "arodnap.orchestrator.results.discover_patch_tool",
                    return_value=PatchTool(
                        binary="/opt/homebrew/bin/gpatch",
                        flavor="gnu",
                        version="GNU patch 2.7.6",
                    ),
                ):
                    write_run_manifest(
                        layout,
                        state,
                        success=True,
                        run_metadata=run_metadata,
                        analysis_runs=analysis_runs,
                        stage_timings=stage_timings,
                    )
                    write_report(
                        layout,
                        state,
                        success=True,
                        run_metadata=run_metadata,
                        analysis_runs=analysis_runs,
                        stage_timings=stage_timings,
                    )

            self.assertEqual(json.loads(layout.patches_manifest_path.read_text()), stage_manifest_payload)

            manifest = json.loads(layout.manifest_path.read_text())
            self.assertTrue(manifest["success"])
            self.assertEqual(manifest["final_patch_manifest"], str(layout.patches_manifest_path))
            self.assertEqual(manifest["adapter_name"], "gradle-v1")
            self.assertEqual(manifest["run_metadata"]["tool_version"], "0+local")
            self.assertEqual(manifest["run_metadata"]["command"], "repair")
            self.assertEqual(manifest["run_metadata"]["selected_build_tool"], ["./gradlew"])
            self.assertEqual(manifest["run_metadata"]["java_version"], 'openjdk version "21.0.2"')
            self.assertEqual(manifest["artifacts"]["patch_bundle_dir"], str(layout.patches_dir))
            self.assertEqual(manifest["analysis_runs"], analysis_runs)
            self.assertEqual(manifest["stage_timings"], stage_timings)
            self.assertEqual(manifest["stage_execution_summary"]["executed"], 1)

            report = json.loads(layout.report_path.read_text())
            self.assertTrue(report["success"])
            self.assertEqual(report["artifacts"]["patches_manifest"], str(layout.patches_manifest_path))
            self.assertEqual(report["diagnostics"]["final_warning_count"], 2)
            self.assertEqual(report["executed_stages"][0]["stage"], "close_injector")
            self.assertEqual(report["repo_root"], "/repo")
            self.assertEqual(report["adapter"]["build_tool_source"], "wrapper")
            self.assertEqual(report["analysis_runs"], analysis_runs)
            self.assertEqual(report["stage_timings"], stage_timings)

    def test_failure_reports_include_error_type_and_failed_timing_entries(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            layout = OutputLayout.from_root(temp_root / "arodnap-out")
            layout.ensure()
            state = self._make_state(layout)

            write_run_manifest(
                layout,
                state,
                success=False,
                error="boom",
                error_type="RuntimeError",
                analysis_runs=[
                    {
                        "label": "initial",
                        "success": False,
                        "error": "boom",
                        "error_type": "RuntimeError",
                    }
                ],
                stage_timings=[],
            )
            write_report(
                layout,
                state,
                success=False,
                error="boom",
                error_type="RuntimeError",
                analysis_runs=[],
                stage_timings=[
                    {
                        "stage": "close_injector",
                        "success": False,
                        "error": "boom",
                        "error_type": "RuntimeError",
                    }
                ],
            )

            manifest = json.loads(layout.manifest_path.read_text())
            report = json.loads(layout.report_path.read_text())
            self.assertEqual(manifest["error_type"], "RuntimeError")
            self.assertEqual(report["error_type"], "RuntimeError")
            self.assertEqual(report["stage_execution_summary"]["failed_attempts"], 1)

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
