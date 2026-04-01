from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from arodnap.contracts import PipelineState, ReanalyzeResult, StageResult


@dataclass(frozen=True)
class AnalysisOutputPaths:
    label: str
    metadata_dir: Path
    wpi_log_path: Path
    inference_dir: Path
    diagnostics_path: Path
    source_files_file: Path
    app_classes_file: Path
    classpath_entries_file: Path
    adapter_metadata_path: Path

    def ensure(self) -> None:
        self.metadata_dir.mkdir(parents=True, exist_ok=True)
        self.wpi_log_path.parent.mkdir(parents=True, exist_ok=True)
        self.inference_dir.parent.mkdir(parents=True, exist_ok=True)
        self.diagnostics_path.parent.mkdir(parents=True, exist_ok=True)


@dataclass(frozen=True)
class OutputLayout:
    root: Path
    report_path: Path
    manifest_path: Path
    diagnostics_dir: Path
    inference_dir: Path
    logs_dir: Path
    stages_dir: Path
    patches_dir: Path
    patches_manifest_path: Path

    @classmethod
    def from_root(cls, root: Path) -> "OutputLayout":
        resolved_root = root.resolve()
        return cls(
            root=resolved_root,
            report_path=resolved_root / "report.json",
            manifest_path=resolved_root / "manifest.json",
            diagnostics_dir=resolved_root / "diagnostics",
            inference_dir=resolved_root / "inference",
            logs_dir=resolved_root / "logs",
            stages_dir=resolved_root / "stages",
            patches_dir=resolved_root / "patches",
            patches_manifest_path=resolved_root / "patches" / "manifest.json",
        )

    def ensure(self) -> None:
        for directory in (
            self.root,
            self.diagnostics_dir,
            self.inference_dir,
            self.logs_dir,
            self.stages_dir,
            self.patches_dir,
        ):
            directory.mkdir(parents=True, exist_ok=True)

    def stage_dir(self, stage_name: str) -> Path:
        return self.stages_dir / stage_name

    def analysis_paths(self, label: str) -> AnalysisOutputPaths:
        metadata_dir = self.logs_dir / label
        return AnalysisOutputPaths(
            label=label,
            metadata_dir=metadata_dir,
            wpi_log_path=metadata_dir / "wpi.log",
            inference_dir=self.inference_dir / label,
            diagnostics_path=self.diagnostics_dir / f"{label}.txt",
            source_files_file=metadata_dir / "source-files.txt",
            app_classes_file=metadata_dir / "app-classes.txt",
            classpath_entries_file=metadata_dir / "classpath-entries.txt",
            adapter_metadata_path=metadata_dir / "adapter-metadata.json",
        )

    def promote_patch_manifest(self, stage_manifest_path: Path) -> Path:
        stage_manifest_path = stage_manifest_path.resolve()
        if not stage_manifest_path.is_file():
            raise FileNotFoundError(f"Stage patch manifest not found: {stage_manifest_path}")
        payload = json.loads(stage_manifest_path.read_text())
        return write_json(self.patches_manifest_path, payload)


def write_json(path: Path, payload: Any) -> Path:
    resolved = path.resolve()
    resolved.parent.mkdir(parents=True, exist_ok=True)
    resolved.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")
    return resolved


def write_run_manifest(
    output_layout: OutputLayout,
    state: PipelineState,
    *,
    success: bool,
    error: str | None = None,
) -> Path:
    payload = {
        **state.to_dict(),
        "error": error,
        "success": success,
    }
    return write_json(output_layout.manifest_path, payload)


def write_report(
    output_layout: OutputLayout,
    state: PipelineState,
    *,
    success: bool,
    error: str | None = None,
) -> Path:
    current_analysis = state.current_analysis
    payload = {
        "artifacts": {
            "diagnostics_dir": str(output_layout.diagnostics_dir),
            "inference_dir": str(output_layout.inference_dir),
            "logs_dir": str(output_layout.logs_dir),
            "manifest": str(output_layout.manifest_path),
            "patches_manifest": (
                str(state.final_patch_manifest) if state.final_patch_manifest is not None else None
            ),
            "report": str(output_layout.report_path),
            "stages_dir": str(output_layout.stages_dir),
        },
        "diagnostics": {
            "final_diagnostics_path": (
                str(current_analysis.diagnostics_path) if current_analysis is not None else None
            ),
            "final_warning_count": current_analysis.warning_count if current_analysis is not None else None,
        },
        "error": error,
        "executed_stages": [
            {
                "artifacts": dict(stage.artifacts),
                "changed": stage.changed,
                "rerun_required": stage.rerun_required,
                "stage": stage.stage,
                "success": stage.success,
            }
            for stage in state.stage_history
        ],
        "final_analysis": current_analysis.to_dict() if current_analysis is not None else None,
        "success": success,
        "workspace_root": str(state.workspace_root),
    }
    return write_json(output_layout.report_path, payload)


__all__ = [
    "AnalysisOutputPaths",
    "OutputLayout",
    "PipelineState",
    "ReanalyzeResult",
    "StageResult",
    "write_json",
    "write_report",
    "write_run_manifest",
]
