import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from arodnap.contracts import ReanalyzeResult, RunConfig, StageResult, Timeouts
from arodnap.orchestrator.pipeline import run_repair
from arodnap.orchestrator.results import OutputLayout


class RepairPipelineTest(unittest.TestCase):
    def test_run_repair_emits_top_level_output_layout_and_reports(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            repo_root = temp_root / "repo"
            (repo_root / "src" / "main" / "java").mkdir(parents=True)
            (repo_root / "src" / "main" / "java" / "App.java").write_text("class App {}\n")
            config = self._make_config(temp_root, repo_root=repo_root)
            layout = OutputLayout.from_root(config.out_dir)

            analysis_calls: list[str] = []

            def fake_reanalyze(config, *, workspace_root, label, artifacts_root):
                analysis_calls.append(label)
                output_layout = OutputLayout.from_root(artifacts_root)
                analysis_paths = output_layout.analysis_paths(label)
                analysis_paths.ensure()
                compiled_outputs_root = workspace_root / "build" / "classes" / "java" / "main"
                compiled_outputs_root.mkdir(parents=True, exist_ok=True)
                (compiled_outputs_root / "App.class").write_bytes(b"class")
                analysis_paths.source_files_file.write_text(
                    str((workspace_root / "src" / "main" / "java" / "App.java").resolve()) + "\n"
                )
                analysis_paths.app_classes_file.write_text("App\n")
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
                            "source_root": str((workspace_root / "src" / "main" / "java").resolve()),
                        },
                        indent=2,
                        sort_keys=True,
                    )
                    + "\n"
                )
                analysis_paths.wpi_log_path.write_text("wpi\n")
                analysis_paths.inference_dir.mkdir(parents=True, exist_ok=True)
                (analysis_paths.inference_dir / "App.ajava").write_text("inferred\n")
                analysis_paths.diagnostics_path.write_text(f"{label}: warning\n")
                return ReanalyzeResult(
                    workspace_root=workspace_root.resolve(),
                    label=label,
                    wpi_log_path=analysis_paths.wpi_log_path.resolve(),
                    inference_dir=analysis_paths.inference_dir.resolve(),
                    diagnostics_path=analysis_paths.diagnostics_path.resolve(),
                    warning_count=2 if label == "initial" else 1,
                    source_files_file=analysis_paths.source_files_file.resolve(),
                    app_classes_file=analysis_paths.app_classes_file.resolve(),
                    classpath_entries_file=analysis_paths.classpath_entries_file.resolve(),
                    adapter_metadata_path=analysis_paths.adapter_metadata_path.resolve(),
                )

            def fake_stage(name, *, changed_files=(), extra_artifacts=None):
                def run(*args, stage_output_dir, workspace_root, **kwargs):
                    stage_output_dir.mkdir(parents=True, exist_ok=True)
                    log_path = stage_output_dir / "stage.log"
                    log_path.write_text(f"{name}\n")
                    for changed_file in changed_files:
                        target = workspace_root / changed_file
                        target.write_text(target.read_text().replace("class App", "final class App"))
                    artifacts = {"log": str(log_path.resolve())}
                    artifacts.update((extra_artifacts or (lambda _dir: {}))(stage_output_dir))
                    return StageResult(
                        stage=name,
                        changed=bool(changed_files),
                        changed_files=list(changed_files),
                        rerun_required=bool(changed_files),
                        artifacts=artifacts,
                        notes=[name],
                        success=True,
                    )

                return run

            def rlfixer_artifacts(stage_output_dir):
                (stage_output_dir / "fixes.txt").write_text("fixes\n")
                (stage_output_dir / "debug.txt").write_text("debug\n")
                return {
                    "fixes": str((stage_output_dir / "fixes.txt").resolve()),
                    "debug": str((stage_output_dir / "debug.txt").resolve()),
                }

            with patch("arodnap.orchestrator.pipeline.reanalyze", side_effect=fake_reanalyze):
                with patch(
                    "arodnap.stages.registry.run_close_injector_stage",
                    side_effect=fake_stage("close_injector", changed_files=["src/main/java/App.java"]),
                ):
                    with patch(
                        "arodnap.stages.registry.run_owning_field_stage",
                        side_effect=fake_stage("owning_field"),
                    ):
                        with patch(
                            "arodnap.stages.registry.run_rlfixer_stage",
                            side_effect=fake_stage("rlfixer", extra_artifacts=rlfixer_artifacts),
                        ):
                            with patch(
                                "arodnap.stages.registry.run_rlpatcher_stage",
                                side_effect=fake_stage("rlpatcher"),
                            ):
                                self.assertEqual(run_repair(config), 0)

            self.assertEqual(analysis_calls, ["initial", "post_close_injector"])
            self.assertTrue(layout.report_path.is_file())
            self.assertTrue(layout.manifest_path.is_file())
            self.assertTrue(layout.diagnostics_dir.is_dir())
            self.assertTrue(layout.inference_dir.is_dir())
            self.assertTrue(layout.logs_dir.is_dir())
            self.assertTrue(layout.stages_dir.is_dir())
            self.assertTrue(layout.patches_manifest_path.is_file())
            self.assertTrue(layout.stage_dir("close_injector").is_dir())
            self.assertTrue(layout.stage_dir("owning_field").is_dir())
            self.assertTrue(layout.stage_dir("rlfixer").is_dir())
            self.assertTrue(layout.stage_dir("rlpatcher").is_dir())
            self.assertTrue(layout.stage_dir("bundle").is_dir())

            # The bundle is the diff between the original repo and the final workspace.
            promoted_manifest = json.loads(layout.patches_manifest_path.read_text())
            self.assertEqual(promoted_manifest["stage"], "bundle")
            [entry] = promoted_manifest["patches"]
            self.assertEqual(entry["changed_files"], ["src/main/java/App.java"])
            self.assertEqual(entry["patch_file"], "arodnap.patch")
            self.assertIn("+final class App {}", (layout.patches_dir / "arodnap.patch").read_text())
            self.assertEqual((repo_root / "src" / "main" / "java" / "App.java").read_text(), "class App {}\n")

            manifest = json.loads(layout.manifest_path.read_text())
            self.assertTrue(manifest["success"])
            self.assertEqual(manifest["final_patch_manifest"], str(layout.patches_manifest_path))
            self.assertEqual(len(manifest["stage_history"]), 5)
            self.assertEqual(manifest["run_metadata"]["command"], "repair")
            self.assertEqual(manifest["adapter"]["selected_build_tool"], ["gradle"])
            self.assertEqual(len(manifest["analysis_runs"]), 2)
            self.assertEqual(len(manifest["stage_timings"]), 5)

            report = json.loads(layout.report_path.read_text())
            self.assertTrue(report["success"])
            self.assertEqual(report["diagnostics"]["final_warning_count"], 1)
            self.assertEqual(
                [stage["stage"] for stage in report["executed_stages"]],
                ["close_injector", "owning_field", "rlfixer", "rlpatcher", "bundle"],
            )
            self.assertEqual(report["artifacts"]["patches_manifest"], str(layout.patches_manifest_path))
            self.assertEqual(report["artifacts"]["patch_bundle_dir"], str(layout.patches_dir))
            self.assertEqual(report["stage_execution_summary"]["executed"], 5)
            self.assertEqual(report["stage_execution_summary"]["reruns_requested"], 1)

    def _make_config(self, root: Path, *, repo_root: Path) -> RunConfig:
        return RunConfig(
            command="repair",
            repo_root=repo_root.resolve(),
            out_dir=(root / "arodnap-out").resolve(),
            keep_workspace=False,
            workspace_mode="copy",
            build_args=[],
            compile_target="classes",
            patch_dir=None,
            cf_root=(root / "cf").resolve(),
            close_injector_jar=(root / "close.jar").resolve(),
            owning_field_jar=(root / "owning.jar").resolve(),
            rlfixer_jar=(root / "rlfixer.jar").resolve(),
            rlpatcher_jar=(root / "rlpatcher.jar").resolve(),
            timeouts=Timeouts(),
        )


if __name__ == "__main__":
    unittest.main()
