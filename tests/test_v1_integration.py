from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from arodnap.cli.main import main
from arodnap.orchestrator.results import OutputLayout
from tests.fixture_helpers import (
    BASELINE_SCENARIO,
    CLOSE_INJECTOR_SCENARIO,
    FixtureRepairHarness,
    OWNING_FIELD_SCENARIO,
    copy_fixture,
    workspace_text_snapshot,
)


class V1IntegrationTest(unittest.TestCase):
    def test_plain_gradle_baseline_repair_and_apply_use_workspace_copy(self) -> None:
        self._assert_repair_and_apply_flow(
            scenario=BASELINE_SCENARIO,
            expected_analysis_calls=["initial", "final"],
            expected_final_warning_count=1,
            changed_stage=None,
        )

    def test_close_injector_case_reruns_analysis_via_reanalyze(self) -> None:
        self._assert_repair_and_apply_flow(
            scenario=CLOSE_INJECTOR_SCENARIO,
            expected_analysis_calls=["initial", "post_close_injector", "final"],
            expected_final_warning_count=1,
            changed_stage="close_injector",
        )

    def test_owning_field_case_reruns_analysis_via_reanalyze(self) -> None:
        self._assert_repair_and_apply_flow(
            scenario=OWNING_FIELD_SCENARIO,
            expected_analysis_calls=["initial", "post_owning_field", "final"],
            expected_final_warning_count=1,
            changed_stage="owning_field",
        )

    def _assert_repair_and_apply_flow(
        self,
        *,
        scenario,
        expected_analysis_calls: list[str],
        expected_final_warning_count: int,
        changed_stage: str | None,
    ) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            repo_root = copy_fixture("gradle-pipeline-baseline", temp_root)
            out_dir = temp_root / "arodnap-out"
            before_snapshot = workspace_text_snapshot(repo_root)
            harness = FixtureRepairHarness(repo_root=repo_root, scenario=scenario)

            with harness.patch_pipeline():
                self.assertEqual(
                    main(["repair", "--out-dir", str(out_dir), str(repo_root)]),
                    0,
                )

            self.assertEqual(harness.analysis_calls, expected_analysis_calls)
            self.assertEqual(workspace_text_snapshot(repo_root), before_snapshot)

            layout = OutputLayout.from_root(out_dir)
            self.assertTrue(layout.report_path.is_file())
            self.assertTrue(layout.manifest_path.is_file())
            self.assertTrue(layout.patches_manifest_path.is_file())
            self.assertTrue(layout.diagnostics_dir.is_dir())
            self.assertTrue(layout.inference_dir.is_dir())
            self.assertTrue(layout.logs_dir.is_dir())
            self.assertTrue(layout.stages_dir.is_dir())

            manifest = json.loads(layout.manifest_path.read_text())
            report = json.loads(layout.report_path.read_text())
            top_level_patch_manifest = json.loads(layout.patches_manifest_path.read_text())

            self.assertTrue(manifest["success"])
            self.assertFalse(manifest["legacy_regression_enabled"])
            self.assertEqual(manifest["config"]["workspace_mode"], "copy")
            self.assertEqual(manifest["final_patch_manifest"], str(layout.patches_manifest_path))
            self.assertNotEqual(Path(manifest["workspace_root"]), repo_root.resolve())
            self.assertFalse(Path(manifest["workspace_root"]).exists())

            self.assertTrue(report["success"])
            self.assertEqual(report["diagnostics"]["final_warning_count"], expected_final_warning_count)
            self.assertEqual(report["artifacts"]["patches_manifest"], str(layout.patches_manifest_path))

            self.assertEqual(
                [stage["stage"] for stage in manifest["stage_history"]],
                ["close_injector", "owning_field", "rlfixer", "rlpatcher", "bundle"],
            )
            # One bundle carries every stage's changes relative to the original repo.
            self.assertEqual(top_level_patch_manifest["stage"], "bundle")
            self.assertEqual(len(top_level_patch_manifest["patches"]), 1)
            expected_changed = {scenario.patch_target}
            if changed_stage == "close_injector":
                expected_changed.add("src/main/java/com/arodnap/fixture/WrapperMissingClose.java")
            if changed_stage == "owning_field":
                expected_changed.add("src/main/java/com/arodnap/fixture/OwningFieldReassignment.java")
            self.assertEqual(set(top_level_patch_manifest["patches"][0]["changed_files"]), expected_changed)

            self._assert_stage_artifacts(layout, changed_stage=changed_stage)

            self.assertEqual(
                main(
                    [
                        "apply",
                        "--out-dir",
                        str(out_dir),
                        "--patch-dir",
                        str(layout.patches_dir),
                        str(repo_root),
                    ]
                ),
                0,
            )

            self.assertNotEqual(workspace_text_snapshot(repo_root), before_snapshot)
            self.assertTrue((layout.logs_dir / "apply.log").is_file())
            self.assertIn("PATCH_BINARY:", (layout.logs_dir / "apply.log").read_text())

    def _assert_stage_artifacts(self, layout: OutputLayout, *, changed_stage: str | None) -> None:
        close_dir = layout.stage_dir("close_injector")
        owning_dir = layout.stage_dir("owning_field")
        rlfixer_dir = layout.stage_dir("rlfixer")
        rlpatcher_dir = layout.stage_dir("rlpatcher")

        self.assertTrue((close_dir / "stage_result.json").is_file())
        self.assertTrue((close_dir / "stage.log").is_file())
        self.assertEqual((close_dir / "close_injector.patch").exists(), changed_stage == "close_injector")

        self.assertTrue((owning_dir / "stage_result.json").is_file())
        self.assertTrue((owning_dir / "stage.log").is_file())
        self.assertEqual((owning_dir / "owning_field.patch").exists(), changed_stage == "owning_field")

        self.assertTrue((rlfixer_dir / "stage_result.json").is_file())
        self.assertTrue((rlfixer_dir / "stage.log").is_file())
        self.assertTrue((rlfixer_dir / "fixes.txt").is_file())
        self.assertTrue((rlfixer_dir / "debug.txt").is_file())

        self.assertTrue((rlpatcher_dir / "stage_result.json").is_file())
        self.assertTrue((rlpatcher_dir / "stage.log").is_file())
        self.assertTrue((rlpatcher_dir / "patch_manifest.json").is_file())
        self.assertEqual(len(list((rlpatcher_dir / "patches").glob("*.patch"))), 1)

        bundle_dir = layout.stage_dir("bundle")
        self.assertTrue((bundle_dir / "stage_result.json").is_file())
        self.assertTrue((layout.patches_dir / "arodnap.patch").is_file())


if __name__ == "__main__":
    unittest.main()
