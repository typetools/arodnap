import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from arodnap.compat.rlfixer_inputs import RLFixerCompatibilityBundle
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
                        {"compiled_classes_root": str(compiled_outputs_root.resolve())},
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

            def fake_close(config, *, workspace_root, diagnostics_path, stage_output_dir):
                stage_output_dir.mkdir(parents=True, exist_ok=True)
                (stage_output_dir / "stage.log").write_text("close\n")
                (stage_output_dir / "stage_result.json").write_text("{}\n")
                return StageResult(
                    stage="close_injector",
                    changed=True,
                    changed_files=["src/main/java/App.java"],
                    rerun_required=True,
                    artifacts={"log": str((stage_output_dir / "stage.log").resolve())},
                    notes=["patched"],
                    success=True,
                )

            def fake_owning(config, *, workspace_root, diagnostics_path, stage_output_dir):
                stage_output_dir.mkdir(parents=True, exist_ok=True)
                (stage_output_dir / "stage.log").write_text("owning\n")
                (stage_output_dir / "stage_result.json").write_text("{}\n")
                return StageResult(
                    stage="owning_field",
                    changed=False,
                    changed_files=[],
                    rerun_required=False,
                    artifacts={"log": str((stage_output_dir / "stage.log").resolve())},
                    notes=["noop"],
                    success=True,
                )

            def fake_bundle(
                *,
                workspace_root,
                source_files_file,
                app_classes_file,
                classpath_entries_file,
                compiled_outputs_root,
                stage_output_dir,
            ):
                bundle_root = stage_output_dir / "compat_bundle"
                info_dir = bundle_root / "info"
                jar_dir = bundle_root / "jarfile"
                info_dir.mkdir(parents=True, exist_ok=True)
                jar_dir.mkdir(parents=True, exist_ok=True)
                classes_file = info_dir / "classes"
                sources_file = info_dir / "sources"
                jar_path = jar_dir / "repo.jar"
                metadata_path = bundle_root / "metadata.json"
                classes_file.write_text("App\n")
                sources_file.write_text("src/main/java/App.java\n")
                jar_path.write_bytes(b"jar")
                metadata_path.write_text("{}\n")
                return RLFixerCompatibilityBundle(
                    root=bundle_root.resolve(),
                    info_dir=info_dir.resolve(),
                    classes_file=classes_file.resolve(),
                    sources_file=sources_file.resolve(),
                    jar_dir=jar_dir.resolve(),
                    jar_path=jar_path.resolve(),
                    metadata_path=metadata_path.resolve(),
                )

            def fake_rlfixer(*, workspace_root, diagnostics_path, inference_dir, compatibility_bundle_root, stage_output_dir):
                stage_output_dir.mkdir(parents=True, exist_ok=True)
                fixes_path = stage_output_dir / "fixes.txt"
                debug_path = stage_output_dir / "debug.txt"
                log_path = stage_output_dir / "stage.log"
                fixes_path.write_text("fixes\n")
                debug_path.write_text("debug\n")
                log_path.write_text("rlfixer\n")
                (stage_output_dir / "stage_result.json").write_text("{}\n")
                return StageResult(
                    stage="rlfixer",
                    changed=False,
                    changed_files=[],
                    rerun_required=False,
                    artifacts={
                        "log": str(log_path.resolve()),
                        "fixes": str(fixes_path.resolve()),
                        "debug": str(debug_path.resolve()),
                        "compatibility_bundle_metadata": str((compatibility_bundle_root / "metadata.json").resolve()),
                    },
                    notes=["rlfixer"],
                    success=True,
                )

            def fake_rlpatcher(
                *,
                workspace_root,
                diagnostics_path,
                inference_dir,
                fixes_path,
                debug_path,
                stage_output_dir,
                rlpatcher_jar,
            ):
                stage_output_dir.mkdir(parents=True, exist_ok=True)
                patch_dir = stage_output_dir / "patches"
                patch_dir.mkdir(parents=True, exist_ok=True)
                patch_path = patch_dir / "app.patch"
                patch_path.write_text("--- src/main/java/App.java\n+++ src/main/java/App.java\n")
                stage_manifest_path = stage_output_dir / "patch_manifest.json"
                stage_manifest_payload = {
                    "patches": [
                        {
                            "changed_files": ["src/main/java/App.java"],
                            "patch_file": str(patch_path.resolve()),
                            "preimage_hashes": {"src/main/java/App.java": "abc"},
                            "stage": "rlpatcher",
                            "strip_level": 0,
                            "target_root": ".",
                        }
                    ],
                    "stage": "rlpatcher",
                }
                stage_manifest_path.write_text(json.dumps(stage_manifest_payload, indent=2, sort_keys=True) + "\n")
                (stage_output_dir / "stage.log").write_text("rlpatcher\n")
                (stage_output_dir / "stage_result.json").write_text("{}\n")
                return StageResult(
                    stage="rlpatcher",
                    changed=False,
                    changed_files=[],
                    rerun_required=False,
                    artifacts={
                        "log": str((stage_output_dir / "stage.log").resolve()),
                        "patch_manifest": str(stage_manifest_path.resolve()),
                        "patch_dir": str(patch_dir.resolve()),
                    },
                    notes=["materialized"],
                    success=True,
                )

            with patch("arodnap.orchestrator.pipeline.reanalyze", side_effect=fake_reanalyze):
                with patch("arodnap.orchestrator.pipeline.run_close_injector_stage", side_effect=fake_close):
                    with patch("arodnap.orchestrator.pipeline.run_owning_field_stage", side_effect=fake_owning):
                        with patch(
                            "arodnap.orchestrator.pipeline.generate_rlfixer_compatibility_bundle",
                            side_effect=fake_bundle,
                        ):
                            with patch("arodnap.orchestrator.pipeline.run_rlfixer_stage", side_effect=fake_rlfixer):
                                with patch(
                                    "arodnap.orchestrator.pipeline.run_rlpatcher_stage",
                                    side_effect=fake_rlpatcher,
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

            promoted_manifest = json.loads(layout.patches_manifest_path.read_text())
            self.assertEqual(promoted_manifest["stage"], "rlpatcher")
            self.assertEqual(
                promoted_manifest["patches"][0]["changed_files"],
                ["src/main/java/App.java"],
            )

            manifest = json.loads(layout.manifest_path.read_text())
            self.assertTrue(manifest["success"])
            self.assertEqual(manifest["final_patch_manifest"], str(layout.patches_manifest_path))
            self.assertEqual(len(manifest["stage_history"]), 4)

            report = json.loads(layout.report_path.read_text())
            self.assertTrue(report["success"])
            self.assertEqual(report["diagnostics"]["final_warning_count"], 1)
            self.assertEqual(
                [stage["stage"] for stage in report["executed_stages"]],
                ["close_injector", "owning_field", "rlfixer", "rlpatcher"],
            )
            self.assertEqual(report["artifacts"]["patches_manifest"], str(layout.patches_manifest_path))

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
            rlpatcher_jar=(root / "rlpatcher.jar").resolve(),
            timeouts=Timeouts(build_seconds=1, analysis_seconds=2, stage_seconds=3),
        )


if __name__ == "__main__":
    unittest.main()
