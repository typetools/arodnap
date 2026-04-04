from __future__ import annotations

from datetime import datetime, timezone
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from arodnap.contracts import PipelineState, ReanalyzeResult, StageResult
from arodnap.patch_tool import PatchToolError, discover_patch_tool
from arodnap.runtime import CommandExecutionError, run_command
from arodnap.version import __version__


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
    error_type: str | None = None,
    run_metadata: dict[str, Any] | None = None,
    analysis_runs: list[dict[str, Any]] | None = None,
    stage_timings: list[dict[str, Any]] | None = None,
) -> Path:
    adapter_summary = _build_adapter_summary(state)
    payload = {
        **state.to_dict(),
        "error": error,
        "error_type": error_type,
        "success": success,
        "artifacts": _artifact_payload(output_layout, state),
        "run_metadata": _build_enriched_run_metadata(
            state=state,
            output_layout=output_layout,
            run_metadata=run_metadata or {},
            adapter_summary=adapter_summary,
        ),
        "adapter": adapter_summary,
        "analysis_runs": analysis_runs or [],
        "stage_timings": stage_timings or [],
        "stage_execution_summary": _stage_execution_summary(state, stage_timings or []),
    }
    return write_json(output_layout.manifest_path, payload)


def write_report(
    output_layout: OutputLayout,
    state: PipelineState,
    *,
    success: bool,
    error: str | None = None,
    error_type: str | None = None,
    run_metadata: dict[str, Any] | None = None,
    analysis_runs: list[dict[str, Any]] | None = None,
    stage_timings: list[dict[str, Any]] | None = None,
) -> Path:
    current_analysis = state.current_analysis
    adapter_summary = _build_adapter_summary(state)
    payload = {
        "artifacts": _artifact_payload(output_layout, state),
        "diagnostics": {
            "final_diagnostics_path": (
                str(current_analysis.diagnostics_path) if current_analysis is not None else None
            ),
            "final_warning_count": current_analysis.warning_count if current_analysis is not None else None,
        },
        "error": error,
        "error_type": error_type,
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
        "repo_root": str(state.config.repo_root),
        "run_metadata": _build_enriched_run_metadata(
            state=state,
            output_layout=output_layout,
            run_metadata=run_metadata or {},
            adapter_summary=adapter_summary,
        ),
        "adapter": adapter_summary,
        "analysis_runs": analysis_runs or [],
        "stage_timings": stage_timings or [],
        "stage_execution_summary": _stage_execution_summary(state, stage_timings or []),
    }
    return write_json(output_layout.report_path, payload)


def _artifact_payload(output_layout: OutputLayout, state: PipelineState) -> dict[str, Any]:
    return {
        "diagnostics_dir": str(output_layout.diagnostics_dir),
        "inference_dir": str(output_layout.inference_dir),
        "logs_dir": str(output_layout.logs_dir),
        "manifest": str(output_layout.manifest_path),
        "patch_bundle_dir": str(output_layout.patches_dir),
        "patches_manifest": (
            str(state.final_patch_manifest) if state.final_patch_manifest is not None else None
        ),
        "report": str(output_layout.report_path),
        "stages_dir": str(output_layout.stages_dir),
    }


def _build_enriched_run_metadata(
    *,
    state: PipelineState,
    output_layout: OutputLayout,
    run_metadata: dict[str, Any],
    adapter_summary: dict[str, Any],
) -> dict[str, Any]:
    payload = {
        "tool_version": __version__,
        "command": state.config.command,
        "repo_root": str(state.config.repo_root),
        "workspace_root": str(state.workspace_root),
        "keep_workspace": state.config.keep_workspace,
        "build_args": list(state.config.build_args),
        "compile_target": state.config.compile_target,
        "timeouts": state.config.timeouts.to_dict(),
        "generated_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "output_dir": str(output_layout.root),
        "adapter_name": adapter_summary.get("adapter_name"),
        "selected_build_tool": adapter_summary.get("selected_build_tool"),
        "build_tool_source": adapter_summary.get("build_tool_source"),
        "java_version": _probe_java_version_summary(),
        "patch_tool": _probe_patch_tool_summary(include_patch_tool=state.config.command == "repair"),
    }
    payload.update(run_metadata)
    return payload


def _build_adapter_summary(state: PipelineState) -> dict[str, Any]:
    current_analysis = state.current_analysis
    adapter_payload = _load_json_file(current_analysis.adapter_metadata_path) if current_analysis is not None else None
    return {
        "build_system": _value_or_default(adapter_payload, "build_system", state.build_system),
        "adapter_name": _value_or_default(adapter_payload, "adapter_name", state.adapter_name),
        "selected_build_tool": _value_or_default(adapter_payload, "build_tool", None),
        "build_tool_source": _value_or_default(adapter_payload, "build_tool_source", None),
        "compile_target": _value_or_default(adapter_payload, "compile_target", state.config.compile_target),
        "source_root": _value_or_default(adapter_payload, "source_root", None),
        "compiled_classes_root": _value_or_default(adapter_payload, "compiled_classes_root", None),
        "classpath_entries_file": _value_or_default(adapter_payload, "classpath_entries_file", None),
        "adapter_metadata_path": (
            str(current_analysis.adapter_metadata_path) if current_analysis is not None else None
        ),
    }


def _load_json_file(path: Path) -> dict[str, Any] | None:
    resolved = path.resolve()
    if not resolved.is_file():
        return None
    try:
        payload = json.loads(resolved.read_text())
    except json.JSONDecodeError:
        return None
    return payload if isinstance(payload, dict) else None


def _value_or_default(
    payload: dict[str, Any] | None,
    key: str,
    default: Any,
) -> Any:
    if payload is None:
        return default
    value = payload.get(key)
    return default if value is None else value


def _probe_java_version_summary() -> str | None:
    try:
        completed = run_command(["java", "-version"])
    except CommandExecutionError:
        return None
    output = "\n".join(part for part in (completed.stdout, completed.stderr) if part).strip()
    for line in output.splitlines():
        if line.strip():
            return line.strip()
    return None


def _probe_patch_tool_summary(*, include_patch_tool: bool) -> dict[str, Any] | None:
    if not include_patch_tool:
        return None
    try:
        tool = discover_patch_tool(require_gnu=False, operation_label="report metadata probe")
    except PatchToolError:
        return None
    return {
        "binary": tool.binary,
        "flavor": tool.flavor,
        "version": tool.version,
    }


def _stage_execution_summary(
    state: PipelineState,
    stage_timings: list[dict[str, Any]],
) -> dict[str, Any]:
    return {
        "executed": len(state.stage_history),
        "changed": sum(1 for stage in state.stage_history if stage.changed),
        "reruns_requested": sum(1 for stage in state.stage_history if stage.rerun_required),
        "successful": sum(1 for stage in state.stage_history if stage.success),
        "failed_attempts": sum(1 for timing in stage_timings if timing.get("success") is False),
    }


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
