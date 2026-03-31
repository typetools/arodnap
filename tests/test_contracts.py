import json
import unittest
from pathlib import Path

from arodnap import PipelineState, ReanalyzeResult, RunConfig, StageResult, Timeouts


class ContractsTest(unittest.TestCase):
    def test_run_config_round_trip(self) -> None:
        config = RunConfig(
            command="repair",
            repo_root=Path("/repo"),
            out_dir=Path("/out"),
            keep_workspace=True,
            workspace_mode="copy",
            build_args=["--info"],
            compile_target="classes",
            patch_dir=Path("/patches"),
            cf_root=Path("/cf"),
            close_injector_jar=Path("/jars/close.jar"),
            owning_field_jar=Path("/jars/owning.jar"),
            rlpatcher_jar=Path("/jars/patcher.jar"),
            timeouts=Timeouts(build_seconds=60, analysis_seconds=120, stage_seconds=30),
        )

        payload = json.loads(json.dumps(config.to_dict()))

        self.assertEqual(RunConfig.from_dict(payload), config)

    def test_reanalyze_result_round_trip(self) -> None:
        result = ReanalyzeResult(
            workspace_root=Path("/tmp/workspace"),
            label="post-close-injector",
            wpi_log_path=Path("/out/logs/wpi.log"),
            inference_dir=Path("/out/inference/run"),
            diagnostics_path=Path("/out/diagnostics/run.txt"),
            warning_count=3,
            source_files_file=Path("/out/files/sources.txt"),
            app_classes_file=Path("/out/files/classes.txt"),
            classpath_entries_file=Path("/out/files/classpath.txt"),
            adapter_metadata_path=Path("/out/files/adapter.json"),
        )

        payload = json.loads(json.dumps(result.to_dict()))

        self.assertEqual(ReanalyzeResult.from_dict(payload), result)

    def test_stage_result_round_trip(self) -> None:
        result = StageResult(
            stage="close_injector",
            changed=True,
            changed_files=["src/main/java/Example.java"],
            rerun_required=True,
            artifacts={"patch": "/out/stages/close/patch.diff"},
            notes=["inserted close method"],
            success=True,
        )

        payload = json.loads(json.dumps(result.to_dict()))

        self.assertEqual(StageResult.from_dict(payload), result)

    def test_pipeline_state_round_trip(self) -> None:
        config = RunConfig(
            command="analyze",
            repo_root=Path("/repo"),
            out_dir=Path("/out"),
            keep_workspace=False,
            workspace_mode="copy",
            build_args=[],
            compile_target=None,
            patch_dir=None,
            cf_root=Path("/cf"),
            close_injector_jar=Path("/jars/close.jar"),
            owning_field_jar=Path("/jars/owning.jar"),
            rlpatcher_jar=Path("/jars/patcher.jar"),
            timeouts=Timeouts(build_seconds=10, analysis_seconds=20, stage_seconds=30),
        )
        analysis = ReanalyzeResult(
            workspace_root=Path("/tmp/workspace"),
            label="initial",
            wpi_log_path=Path("/out/logs/wpi.log"),
            inference_dir=Path("/out/inference/initial"),
            diagnostics_path=Path("/out/diagnostics/initial.txt"),
            warning_count=2,
            source_files_file=Path("/out/files/sources.txt"),
            app_classes_file=Path("/out/files/classes.txt"),
            classpath_entries_file=Path("/out/files/classpath.txt"),
            adapter_metadata_path=Path("/out/files/adapter.json"),
        )
        stage = StageResult(
            stage="owning_field",
            changed=False,
            changed_files=[],
            rerun_required=False,
            artifacts={"log": "/out/stages/owning/log.txt"},
            notes=[],
            success=True,
        )
        state = PipelineState(
            config=config,
            workspace_root=Path("/tmp/workspace"),
            build_system="gradle",
            adapter_name="gradle-v1",
            current_analysis=analysis,
            stage_history=[stage],
            artifacts_root=Path("/out"),
            final_patch_manifest=Path("/out/patches/manifest.json"),
            legacy_regression_enabled=False,
        )

        payload = json.loads(json.dumps(state.to_dict()))

        self.assertEqual(PipelineState.from_dict(payload), state)


if __name__ == "__main__":
    unittest.main()
