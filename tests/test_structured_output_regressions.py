from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from arodnap.cli.main import main
from arodnap.orchestrator.results import OutputLayout
from arodnap.patch_tool import PatchTool
from arodnap.runtime import CommandResult
from tests.fixture_helpers import (
    BASELINE_SCENARIO,
    CLOSE_INJECTOR_SCENARIO,
    FixtureRepairHarness,
    copy_fixture,
)


class StructuredOutputRegressionTest(unittest.TestCase):
    def test_baseline_repair_manifest_matches_structured_contract(self) -> None:
        repo_root, layout, manifest, _report = self._run_fixture_repair(BASELINE_SCENARIO)

        self.assertEqual(
            self._project_manifest(manifest, repo_root=repo_root, output_root=layout.root),
            {
                "success": True,
                "error": None,
                "error_type": None,
                "build_system": "gradle",
                "adapter_name": "gradle-v1",
                "legacy_regression_enabled": False,
                "config": {
                    "command": "repair",
                    "workspace_mode": "copy",
                    "keep_workspace": False,
                    "build_args": [],
                    "compile_target": None,
                },
                "workspace_root": "<workspace>",
                "current_analysis": {
                    "workspace_root": "<workspace>",
                    "label": "initial",
                    "warning_count": 1,
                    "wpi_log_path": "<out>/logs/initial/wpi.log",
                    "inference_dir": "<out>/inference/initial",
                    "diagnostics_path": "<out>/diagnostics/initial.txt",
                    "source_files_file": "<out>/logs/initial/source-files.txt",
                    "app_classes_file": "<out>/logs/initial/app-classes.txt",
                    "classpath_entries_file": "<out>/logs/initial/classpath-entries.txt",
                    "adapter_metadata_path": "<out>/logs/initial/adapter-metadata.json",
                },
                "stage_history": [
                    {
                        "stage": "close_injector",
                        "changed": False,
                        "changed_files": [],
                        "rerun_required": False,
                        "artifacts": {"log": "<out>/stages/close_injector/stage.log"},
                        "notes": ["No close-injector patch was generated."],
                        "success": True,
                    },
                    {
                        "stage": "owning_field",
                        "changed": False,
                        "changed_files": [],
                        "rerun_required": False,
                        "artifacts": {"log": "<out>/stages/owning_field/stage.log"},
                        "notes": ["No owning-field patch was generated."],
                        "success": True,
                    },
                    {
                        "stage": "rlfixer",
                        "changed": False,
                        "changed_files": [],
                        "rerun_required": False,
                        "artifacts": {
                            "log": "<out>/stages/rlfixer/stage.log",
                            "fixes": "<out>/stages/rlfixer/fixes.txt",
                            "debug": "<out>/stages/rlfixer/debug.txt",
                            "compatibility_bundle_metadata": "<out>/stages/rlfixer/compat_bundle/metadata.json",
                        },
                        "notes": ["RLFixer completed without mutating workspace sources."],
                        "success": True,
                    },
                    {
                        "stage": "rlpatcher",
                        "changed": False,
                        "changed_files": [],
                        "rerun_required": False,
                        "artifacts": {
                            "log": "<out>/stages/rlpatcher/stage.log",
                            "patch_manifest": "<out>/stages/rlpatcher/patch_manifest.json",
                            "patch_dir": "<out>/stages/rlpatcher/patches",
                        },
                        "notes": ["Materialized 1 normalized patch(es)."],
                        "success": True,
                    },
                ],
                "artifacts_root": "<out>",
                "final_patch_manifest": "<out>/patches/manifest.json",
                "artifacts": {
                    "diagnostics_dir": "<out>/diagnostics",
                    "inference_dir": "<out>/inference",
                    "logs_dir": "<out>/logs",
                    "manifest": "<out>/manifest.json",
                    "patch_bundle_dir": "<out>/patches",
                    "patches_manifest": "<out>/patches/manifest.json",
                    "report": "<out>/report.json",
                    "stages_dir": "<out>/stages",
                },
                "run_metadata": {
                    "tool_version": "1.1.0",
                    "command": "repair",
                    "repo_root": "<repo>",
                    "workspace_root": "<workspace>",
                    "keep_workspace": False,
                    "build_args": [],
                    "compile_target": None,
                    "artifacts_root": "<out>",
                    "output_dir": "<out>",
                    "adapter_name": "gradle-v1",
                    "selected_build_tool": None,
                    "build_tool_source": None,
                    "java_version": 'openjdk version "21.0.2"',
                    "patch_tool": {
                        "binary": "/opt/homebrew/bin/gpatch",
                        "flavor": "gnu",
                        "version": "GNU patch 2.7.6",
                    },
                    "started_at": "<timestamp>",
                    "completed_at": "<timestamp>",
                    "generated_at": "<timestamp>",
                    "elapsed_seconds": "<elapsed>",
                },
                "adapter": {
                    "build_system": "gradle",
                    "adapter_name": "gradle-v1",
                    "selected_build_tool": None,
                    "build_tool_source": None,
                    "compile_target": None,
                    "source_root": None,
                    "compiled_classes_root": "<workspace>/build/classes/java/main",
                    "classpath_entries_file": None,
                    "adapter_metadata_path": "<out>/logs/initial/adapter-metadata.json",
                },
                "analysis_runs": [
                    {
                        "label": "initial",
                        "started_at": "<timestamp>",
                        "completed_at": "<timestamp>",
                        "elapsed_seconds": "<elapsed>",
                        "success": True,
                        "warning_count": 1,
                        "diagnostics_path": "<out>/diagnostics/initial.txt",
                        "inference_dir": "<out>/inference/initial",
                        "wpi_log_path": "<out>/logs/initial/wpi.log",
                        "adapter_metadata_path": "<out>/logs/initial/adapter-metadata.json",
                    }
                ],
                "stage_timings": [
                    {
                        "stage": "close_injector",
                        "started_at": "<timestamp>",
                        "completed_at": "<timestamp>",
                        "elapsed_seconds": "<elapsed>",
                        "success": True,
                        "changed": False,
                        "rerun_required": False,
                        "changed_files": [],
                        "artifacts": {"log": "<out>/stages/close_injector/stage.log"},
                    },
                    {
                        "stage": "owning_field",
                        "started_at": "<timestamp>",
                        "completed_at": "<timestamp>",
                        "elapsed_seconds": "<elapsed>",
                        "success": True,
                        "changed": False,
                        "rerun_required": False,
                        "changed_files": [],
                        "artifacts": {"log": "<out>/stages/owning_field/stage.log"},
                    },
                    {
                        "stage": "rlfixer",
                        "started_at": "<timestamp>",
                        "completed_at": "<timestamp>",
                        "elapsed_seconds": "<elapsed>",
                        "success": True,
                        "changed": False,
                        "rerun_required": False,
                        "changed_files": [],
                        "artifacts": {
                            "log": "<out>/stages/rlfixer/stage.log",
                            "fixes": "<out>/stages/rlfixer/fixes.txt",
                            "debug": "<out>/stages/rlfixer/debug.txt",
                            "compatibility_bundle_metadata": "<out>/stages/rlfixer/compat_bundle/metadata.json",
                        },
                    },
                    {
                        "stage": "rlpatcher",
                        "started_at": "<timestamp>",
                        "completed_at": "<timestamp>",
                        "elapsed_seconds": "<elapsed>",
                        "success": True,
                        "changed": False,
                        "rerun_required": False,
                        "changed_files": [],
                        "artifacts": {
                            "log": "<out>/stages/rlpatcher/stage.log",
                            "patch_manifest": "<out>/stages/rlpatcher/patch_manifest.json",
                            "patch_dir": "<out>/stages/rlpatcher/patches",
                        },
                    },
                ],
                "stage_execution_summary": {
                    "executed": 4,
                    "changed": 0,
                    "reruns_requested": 0,
                    "successful": 4,
                    "failed_attempts": 0,
                },
            },
        )

    def test_baseline_repair_report_matches_structured_contract(self) -> None:
        repo_root, layout, _manifest, report = self._run_fixture_repair(BASELINE_SCENARIO)

        self.assertEqual(
            self._project_report(report, repo_root=repo_root, output_root=layout.root),
            {
                "success": True,
                "error": None,
                "error_type": None,
                "repo_root": "<repo>",
                "workspace_root": "<workspace>",
                "artifacts": {
                    "diagnostics_dir": "<out>/diagnostics",
                    "inference_dir": "<out>/inference",
                    "logs_dir": "<out>/logs",
                    "manifest": "<out>/manifest.json",
                    "patch_bundle_dir": "<out>/patches",
                    "patches_manifest": "<out>/patches/manifest.json",
                    "report": "<out>/report.json",
                    "stages_dir": "<out>/stages",
                },
                "diagnostics": {
                    "final_diagnostics_path": "<out>/diagnostics/initial.txt",
                    "final_warning_count": 1,
                },
                "executed_stages": [
                    {
                        "stage": "close_injector",
                        "changed": False,
                        "rerun_required": False,
                        "success": True,
                        "artifacts": {"log": "<out>/stages/close_injector/stage.log"},
                    },
                    {
                        "stage": "owning_field",
                        "changed": False,
                        "rerun_required": False,
                        "success": True,
                        "artifacts": {"log": "<out>/stages/owning_field/stage.log"},
                    },
                    {
                        "stage": "rlfixer",
                        "changed": False,
                        "rerun_required": False,
                        "success": True,
                        "artifacts": {
                            "log": "<out>/stages/rlfixer/stage.log",
                            "fixes": "<out>/stages/rlfixer/fixes.txt",
                            "debug": "<out>/stages/rlfixer/debug.txt",
                            "compatibility_bundle_metadata": "<out>/stages/rlfixer/compat_bundle/metadata.json",
                        },
                    },
                    {
                        "stage": "rlpatcher",
                        "changed": False,
                        "rerun_required": False,
                        "success": True,
                        "artifacts": {
                            "log": "<out>/stages/rlpatcher/stage.log",
                            "patch_manifest": "<out>/stages/rlpatcher/patch_manifest.json",
                            "patch_dir": "<out>/stages/rlpatcher/patches",
                        },
                    },
                ],
                "final_analysis": {
                    "workspace_root": "<workspace>",
                    "label": "initial",
                    "warning_count": 1,
                    "wpi_log_path": "<out>/logs/initial/wpi.log",
                    "inference_dir": "<out>/inference/initial",
                    "diagnostics_path": "<out>/diagnostics/initial.txt",
                    "source_files_file": "<out>/logs/initial/source-files.txt",
                    "app_classes_file": "<out>/logs/initial/app-classes.txt",
                    "classpath_entries_file": "<out>/logs/initial/classpath-entries.txt",
                    "adapter_metadata_path": "<out>/logs/initial/adapter-metadata.json",
                },
                "run_metadata": {
                    "tool_version": "1.1.0",
                    "command": "repair",
                    "repo_root": "<repo>",
                    "workspace_root": "<workspace>",
                    "keep_workspace": False,
                    "build_args": [],
                    "compile_target": None,
                    "artifacts_root": "<out>",
                    "output_dir": "<out>",
                    "adapter_name": "gradle-v1",
                    "selected_build_tool": None,
                    "build_tool_source": None,
                    "java_version": 'openjdk version "21.0.2"',
                    "patch_tool": {
                        "binary": "/opt/homebrew/bin/gpatch",
                        "flavor": "gnu",
                        "version": "GNU patch 2.7.6",
                    },
                    "started_at": "<timestamp>",
                    "completed_at": "<timestamp>",
                    "generated_at": "<timestamp>",
                    "elapsed_seconds": "<elapsed>",
                },
                "adapter": {
                    "build_system": "gradle",
                    "adapter_name": "gradle-v1",
                    "selected_build_tool": None,
                    "build_tool_source": None,
                    "compile_target": None,
                    "source_root": None,
                    "compiled_classes_root": "<workspace>/build/classes/java/main",
                    "classpath_entries_file": None,
                    "adapter_metadata_path": "<out>/logs/initial/adapter-metadata.json",
                },
                "analysis_runs": [
                    {
                        "label": "initial",
                        "started_at": "<timestamp>",
                        "completed_at": "<timestamp>",
                        "elapsed_seconds": "<elapsed>",
                        "success": True,
                        "warning_count": 1,
                        "diagnostics_path": "<out>/diagnostics/initial.txt",
                        "inference_dir": "<out>/inference/initial",
                        "wpi_log_path": "<out>/logs/initial/wpi.log",
                        "adapter_metadata_path": "<out>/logs/initial/adapter-metadata.json",
                    }
                ],
                "stage_timings": [
                    {
                        "stage": "close_injector",
                        "started_at": "<timestamp>",
                        "completed_at": "<timestamp>",
                        "elapsed_seconds": "<elapsed>",
                        "success": True,
                        "changed": False,
                        "rerun_required": False,
                        "changed_files": [],
                        "artifacts": {"log": "<out>/stages/close_injector/stage.log"},
                    },
                    {
                        "stage": "owning_field",
                        "started_at": "<timestamp>",
                        "completed_at": "<timestamp>",
                        "elapsed_seconds": "<elapsed>",
                        "success": True,
                        "changed": False,
                        "rerun_required": False,
                        "changed_files": [],
                        "artifacts": {"log": "<out>/stages/owning_field/stage.log"},
                    },
                    {
                        "stage": "rlfixer",
                        "started_at": "<timestamp>",
                        "completed_at": "<timestamp>",
                        "elapsed_seconds": "<elapsed>",
                        "success": True,
                        "changed": False,
                        "rerun_required": False,
                        "changed_files": [],
                        "artifacts": {
                            "log": "<out>/stages/rlfixer/stage.log",
                            "fixes": "<out>/stages/rlfixer/fixes.txt",
                            "debug": "<out>/stages/rlfixer/debug.txt",
                            "compatibility_bundle_metadata": "<out>/stages/rlfixer/compat_bundle/metadata.json",
                        },
                    },
                    {
                        "stage": "rlpatcher",
                        "started_at": "<timestamp>",
                        "completed_at": "<timestamp>",
                        "elapsed_seconds": "<elapsed>",
                        "success": True,
                        "changed": False,
                        "rerun_required": False,
                        "changed_files": [],
                        "artifacts": {
                            "log": "<out>/stages/rlpatcher/stage.log",
                            "patch_manifest": "<out>/stages/rlpatcher/patch_manifest.json",
                            "patch_dir": "<out>/stages/rlpatcher/patches",
                        },
                    },
                ],
                "stage_execution_summary": {
                    "executed": 4,
                    "changed": 0,
                    "reruns_requested": 0,
                    "successful": 4,
                    "failed_attempts": 0,
                },
            },
        )

    def test_stage_results_match_structured_contract(self) -> None:
        repo_root, layout, manifest, _report = self._run_fixture_repair(CLOSE_INJECTOR_SCENARIO)

        close_injector_stage = json.loads((layout.stage_dir("close_injector") / "stage_result.json").read_text())
        rlpatcher_stage = json.loads((layout.stage_dir("rlpatcher") / "stage_result.json").read_text())
        workspace_root = Path(manifest["workspace_root"])

        self.assertEqual(
            self._project_stage_result(
                close_injector_stage,
                repo_root=repo_root,
                output_root=layout.root,
                workspace_root=workspace_root,
            ),
            {
                "stage": "close_injector",
                "changed": True,
                "changed_files": ["src/main/java/com/arodnap/fixture/WrapperMissingClose.java"],
                "rerun_required": True,
                "artifacts": {
                    "log": "<out>/stages/close_injector/stage.log",
                    "patch": "<out>/stages/close_injector/close_injector.patch",
                },
                "notes": ["Applied close-injector patch affecting 1 file(s)."],
                "success": True,
            },
        )
        self.assertEqual(
            self._project_stage_result(
                rlpatcher_stage,
                repo_root=repo_root,
                output_root=layout.root,
                workspace_root=workspace_root,
            ),
            {
                "stage": "rlpatcher",
                "changed": False,
                "changed_files": [],
                "rerun_required": False,
                "artifacts": {
                    "log": "<out>/stages/rlpatcher/stage.log",
                    "patch_manifest": "<out>/stages/rlpatcher/patch_manifest.json",
                    "patch_dir": "<out>/stages/rlpatcher/patches",
                },
                "notes": ["Materialized 1 normalized patch(es)."],
                "success": True,
            },
        )

    def _run_fixture_repair(self, scenario) -> tuple[Path, OutputLayout, dict[str, object], dict[str, object]]:
        temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(temp_dir.cleanup)
        temp_root = Path(temp_dir.name)
        repo_root = copy_fixture("gradle-pipeline-baseline", temp_root)
        out_dir = temp_root / "arodnap-out"
        layout = OutputLayout.from_root(out_dir)
        harness = FixtureRepairHarness(repo_root=repo_root, scenario=scenario)

        with harness.patch_pipeline():
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
                    self.assertEqual(
                        main(["repair", "--out-dir", str(out_dir), str(repo_root)]),
                        0,
                    )

        manifest = json.loads(layout.manifest_path.read_text())
        report = json.loads(layout.report_path.read_text())
        return repo_root.resolve(), layout, manifest, report

    def _project_manifest(
        self,
        manifest: dict[str, object],
        *,
        repo_root: Path,
        output_root: Path,
    ) -> dict[str, object]:
        workspace_root = Path(manifest["workspace_root"])
        return {
            "success": manifest["success"],
            "error": manifest["error"],
            "error_type": manifest["error_type"],
            "build_system": manifest["build_system"],
            "adapter_name": manifest["adapter_name"],
            "legacy_regression_enabled": manifest["legacy_regression_enabled"],
            "config": {
                "command": manifest["config"]["command"],
                "workspace_mode": manifest["config"]["workspace_mode"],
                "keep_workspace": manifest["config"]["keep_workspace"],
                "build_args": manifest["config"]["build_args"],
                "compile_target": manifest["config"]["compile_target"],
            },
            "workspace_root": self._normalize_path(
                manifest["workspace_root"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "current_analysis": self._project_analysis_result(
                manifest["current_analysis"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "stage_history": [
                self._project_stage_result(
                    item,
                    repo_root=repo_root,
                    output_root=output_root,
                    workspace_root=workspace_root,
                )
                for item in manifest["stage_history"]
            ],
            "artifacts_root": self._normalize_path(
                manifest["artifacts_root"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "final_patch_manifest": self._normalize_path(
                manifest["final_patch_manifest"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "artifacts": self._project_path_map(
                manifest["artifacts"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "run_metadata": self._project_run_metadata(
                manifest["run_metadata"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "adapter": self._project_adapter_summary(
                manifest["adapter"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "analysis_runs": [
                self._project_analysis_run(
                    item,
                    repo_root=repo_root,
                    output_root=output_root,
                    workspace_root=workspace_root,
                )
                for item in manifest["analysis_runs"]
            ],
            "stage_timings": [
                self._project_stage_timing(
                    item,
                    repo_root=repo_root,
                    output_root=output_root,
                    workspace_root=workspace_root,
                )
                for item in manifest["stage_timings"]
            ],
            "stage_execution_summary": manifest["stage_execution_summary"],
        }

    def _project_report(
        self,
        report: dict[str, object],
        *,
        repo_root: Path,
        output_root: Path,
    ) -> dict[str, object]:
        workspace_root = Path(report["workspace_root"])
        return {
            "success": report["success"],
            "error": report["error"],
            "error_type": report["error_type"],
            "repo_root": self._normalize_path(
                report["repo_root"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "workspace_root": self._normalize_path(
                report["workspace_root"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "artifacts": self._project_path_map(
                report["artifacts"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "diagnostics": {
                "final_diagnostics_path": self._normalize_path(
                    report["diagnostics"]["final_diagnostics_path"],
                    repo_root=repo_root,
                    output_root=output_root,
                    workspace_root=workspace_root,
                ),
                "final_warning_count": report["diagnostics"]["final_warning_count"],
            },
            "executed_stages": [
                self._project_executed_stage(
                    item,
                    repo_root=repo_root,
                    output_root=output_root,
                    workspace_root=workspace_root,
                )
                for item in report["executed_stages"]
            ],
            "final_analysis": self._project_analysis_result(
                report["final_analysis"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "run_metadata": self._project_run_metadata(
                report["run_metadata"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "adapter": self._project_adapter_summary(
                report["adapter"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "analysis_runs": [
                self._project_analysis_run(
                    item,
                    repo_root=repo_root,
                    output_root=output_root,
                    workspace_root=workspace_root,
                )
                for item in report["analysis_runs"]
            ],
            "stage_timings": [
                self._project_stage_timing(
                    item,
                    repo_root=repo_root,
                    output_root=output_root,
                    workspace_root=workspace_root,
                )
                for item in report["stage_timings"]
            ],
            "stage_execution_summary": report["stage_execution_summary"],
        }

    def _project_analysis_result(
        self,
        payload: dict[str, object],
        *,
        repo_root: Path,
        output_root: Path,
        workspace_root: Path,
    ) -> dict[str, object]:
        return {
            "workspace_root": self._normalize_path(
                payload["workspace_root"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "label": payload["label"],
            "warning_count": payload["warning_count"],
            "wpi_log_path": self._normalize_path(
                payload["wpi_log_path"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "inference_dir": self._normalize_path(
                payload["inference_dir"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "diagnostics_path": self._normalize_path(
                payload["diagnostics_path"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "source_files_file": self._normalize_path(
                payload["source_files_file"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "app_classes_file": self._normalize_path(
                payload["app_classes_file"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "classpath_entries_file": self._normalize_path(
                payload["classpath_entries_file"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "adapter_metadata_path": self._normalize_path(
                payload["adapter_metadata_path"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
        }

    def _project_stage_result(
        self,
        payload: dict[str, object],
        *,
        repo_root: Path,
        output_root: Path,
        workspace_root: Path,
    ) -> dict[str, object]:
        return {
            "stage": payload["stage"],
            "changed": payload["changed"],
            "changed_files": payload["changed_files"],
            "rerun_required": payload["rerun_required"],
            "artifacts": self._project_path_map(
                payload["artifacts"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "notes": payload["notes"],
            "success": payload["success"],
        }

    def _project_executed_stage(
        self,
        payload: dict[str, object],
        *,
        repo_root: Path,
        output_root: Path,
        workspace_root: Path,
    ) -> dict[str, object]:
        return {
            "stage": payload["stage"],
            "changed": payload["changed"],
            "rerun_required": payload["rerun_required"],
            "success": payload["success"],
            "artifacts": self._project_path_map(
                payload["artifacts"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
        }

    def _project_run_metadata(
        self,
        payload: dict[str, object],
        *,
        repo_root: Path,
        output_root: Path,
        workspace_root: Path,
    ) -> dict[str, object]:
        return {
            "tool_version": payload["tool_version"],
            "command": payload["command"],
            "repo_root": self._normalize_path(
                payload["repo_root"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "workspace_root": self._normalize_path(
                payload["workspace_root"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "keep_workspace": payload["keep_workspace"],
            "build_args": payload["build_args"],
            "compile_target": payload["compile_target"],
            "artifacts_root": self._normalize_path(
                payload["artifacts_root"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "output_dir": self._normalize_path(
                payload["output_dir"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "adapter_name": payload["adapter_name"],
            "selected_build_tool": payload["selected_build_tool"],
            "build_tool_source": payload["build_tool_source"],
            "java_version": payload["java_version"],
            "patch_tool": payload["patch_tool"],
            "started_at": self._normalize_timestamp(payload["started_at"]),
            "completed_at": self._normalize_timestamp(payload["completed_at"]),
            "generated_at": self._normalize_timestamp(payload["generated_at"]),
            "elapsed_seconds": self._normalize_elapsed(payload["elapsed_seconds"]),
        }

    def _project_adapter_summary(
        self,
        payload: dict[str, object],
        *,
        repo_root: Path,
        output_root: Path,
        workspace_root: Path,
    ) -> dict[str, object]:
        return {
            "build_system": payload["build_system"],
            "adapter_name": payload["adapter_name"],
            "selected_build_tool": payload["selected_build_tool"],
            "build_tool_source": payload["build_tool_source"],
            "compile_target": payload["compile_target"],
            "source_root": self._normalize_optional_path(
                payload["source_root"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "compiled_classes_root": self._normalize_optional_path(
                payload["compiled_classes_root"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "classpath_entries_file": self._normalize_optional_path(
                payload["classpath_entries_file"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "adapter_metadata_path": self._normalize_optional_path(
                payload["adapter_metadata_path"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
        }

    def _project_analysis_run(
        self,
        payload: dict[str, object],
        *,
        repo_root: Path,
        output_root: Path,
        workspace_root: Path,
    ) -> dict[str, object]:
        return {
            "label": payload["label"],
            "started_at": self._normalize_timestamp(payload["started_at"]),
            "completed_at": self._normalize_timestamp(payload["completed_at"]),
            "elapsed_seconds": self._normalize_elapsed(payload["elapsed_seconds"]),
            "success": payload["success"],
            "warning_count": payload["warning_count"],
            "diagnostics_path": self._normalize_path(
                payload["diagnostics_path"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "inference_dir": self._normalize_path(
                payload["inference_dir"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "wpi_log_path": self._normalize_path(
                payload["wpi_log_path"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
            "adapter_metadata_path": self._normalize_path(
                payload["adapter_metadata_path"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
        }

    def _project_stage_timing(
        self,
        payload: dict[str, object],
        *,
        repo_root: Path,
        output_root: Path,
        workspace_root: Path,
    ) -> dict[str, object]:
        return {
            "stage": payload["stage"],
            "started_at": self._normalize_timestamp(payload["started_at"]),
            "completed_at": self._normalize_timestamp(payload["completed_at"]),
            "elapsed_seconds": self._normalize_elapsed(payload["elapsed_seconds"]),
            "success": payload["success"],
            "changed": payload["changed"],
            "rerun_required": payload["rerun_required"],
            "changed_files": payload["changed_files"],
            "artifacts": self._project_path_map(
                payload["artifacts"],
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            ),
        }

    def _project_path_map(
        self,
        payload: dict[str, object],
        *,
        repo_root: Path,
        output_root: Path,
        workspace_root: Path,
    ) -> dict[str, object]:
        return {
            key: self._normalize_optional_path(
                value,
                repo_root=repo_root,
                output_root=output_root,
                workspace_root=workspace_root,
            )
            for key, value in payload.items()
        }

    def _normalize_optional_path(
        self,
        value: str | None,
        *,
        repo_root: Path,
        output_root: Path,
        workspace_root: Path,
    ) -> str | None:
        if value is None:
            return None
        return self._normalize_path(
            value,
            repo_root=repo_root,
            output_root=output_root,
            workspace_root=workspace_root,
        )

    def _normalize_path(
        self,
        value: str,
        *,
        repo_root: Path,
        output_root: Path,
        workspace_root: Path,
    ) -> str:
        replacements: list[tuple[str, str]] = []
        for raw, token in (
            (str(output_root), "<out>"),
            (str(output_root.resolve()), "<out>"),
            (str(workspace_root), "<workspace>"),
            (str(workspace_root.resolve()), "<workspace>"),
            (str(repo_root), "<repo>"),
            (str(repo_root.resolve()), "<repo>"),
        ):
            if (raw, token) not in replacements:
                replacements.append((raw, token))
        normalized = value
        for original, token in replacements:
            if normalized == original:
                return token
            prefix = original + "/"
            if normalized.startswith(prefix):
                return token + normalized[len(original) :]
        return normalized

    def _normalize_timestamp(self, value: str) -> str:
        self.assertRegex(value, r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
        return "<timestamp>"

    def _normalize_elapsed(self, value: float) -> str:
        self.assertIsInstance(value, float)
        self.assertGreaterEqual(value, 0.0)
        return "<elapsed>"


if __name__ == "__main__":
    unittest.main()
