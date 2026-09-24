from __future__ import annotations

from dataclasses import dataclass, fields
from pathlib import Path
from typing import Any, Literal


def _to_jsonable(value: Any) -> Any:
    if isinstance(value, Path):
        return str(value)
    if hasattr(value, "to_dict"):
        return value.to_dict()
    if isinstance(value, list):
        return [_to_jsonable(item) for item in value]
    if isinstance(value, dict):
        return {key: _to_jsonable(item) for key, item in value.items()}
    return value


def _convert_path(value: Any) -> Path | None:
    if value is None:
        return None
    return Path(value)


@dataclass(frozen=True)
class Timeouts:
    build_seconds: int
    analysis_seconds: int
    stage_seconds: int

    def to_dict(self) -> dict[str, Any]:
        return {field.name: getattr(self, field.name) for field in fields(self)}

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "Timeouts":
        return cls(**data)


@dataclass(frozen=True)
class RunConfig:
    command: Literal["analyze", "infer", "repair", "apply", "doctor"]
    repo_root: Path
    out_dir: Path
    keep_workspace: bool
    workspace_mode: Literal["copy"]
    build_args: list[str]
    compile_target: str | None
    patch_dir: Path | None
    cf_root: Path
    close_injector_jar: Path
    owning_field_jar: Path
    rlfixer_jar: Path
    rlpatcher_jar: Path
    timeouts: Timeouts
    # The project's build command from `-- <command>`; empty means detect the build system.
    build_command: tuple[str, ...] = ()

    def to_dict(self) -> dict[str, Any]:
        return {
            "command": self.command,
            "repo_root": str(self.repo_root),
            "out_dir": str(self.out_dir),
            "keep_workspace": self.keep_workspace,
            "workspace_mode": self.workspace_mode,
            "build_args": list(self.build_args),
            "compile_target": self.compile_target,
            "patch_dir": str(self.patch_dir) if self.patch_dir is not None else None,
            "cf_root": str(self.cf_root),
            "close_injector_jar": str(self.close_injector_jar),
            "owning_field_jar": str(self.owning_field_jar),
            "rlfixer_jar": str(self.rlfixer_jar),
            "rlpatcher_jar": str(self.rlpatcher_jar),
            "timeouts": self.timeouts.to_dict(),
            "build_command": list(self.build_command),
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "RunConfig":
        return cls(
            command=data["command"],
            repo_root=Path(data["repo_root"]),
            out_dir=Path(data["out_dir"]),
            keep_workspace=data["keep_workspace"],
            workspace_mode=data["workspace_mode"],
            build_args=list(data["build_args"]),
            compile_target=data["compile_target"],
            patch_dir=_convert_path(data["patch_dir"]),
            cf_root=Path(data["cf_root"]),
            close_injector_jar=Path(data["close_injector_jar"]),
            owning_field_jar=Path(data["owning_field_jar"]),
            rlfixer_jar=Path(data["rlfixer_jar"]),
            rlpatcher_jar=Path(data["rlpatcher_jar"]),
            timeouts=Timeouts.from_dict(data["timeouts"]),
            build_command=tuple(data.get("build_command", ())),
        )


@dataclass(frozen=True)
class ReanalyzeResult:
    workspace_root: Path
    label: str
    wpi_log_path: Path
    inference_dir: Path
    diagnostics_path: Path
    warning_count: int
    source_files_file: Path
    app_classes_file: Path
    classpath_entries_file: Path
    adapter_metadata_path: Path
    # Limitations of this analysis point, e.g. classes whole-program inference could not cover.
    inference_notes: tuple[str, ...] = ()

    def to_dict(self) -> dict[str, Any]:
        payload = {field.name: _to_jsonable(getattr(self, field.name)) for field in fields(self)}
        payload["inference_notes"] = list(self.inference_notes)
        return payload

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "ReanalyzeResult":
        return cls(
            workspace_root=Path(data["workspace_root"]),
            label=data["label"],
            wpi_log_path=Path(data["wpi_log_path"]),
            inference_dir=Path(data["inference_dir"]),
            diagnostics_path=Path(data["diagnostics_path"]),
            warning_count=data["warning_count"],
            source_files_file=Path(data["source_files_file"]),
            app_classes_file=Path(data["app_classes_file"]),
            classpath_entries_file=Path(data["classpath_entries_file"]),
            adapter_metadata_path=Path(data["adapter_metadata_path"]),
            inference_notes=tuple(data.get("inference_notes", ())),
        )


@dataclass(frozen=True)
class StageResult:
    stage: str
    changed: bool
    changed_files: list[str]
    rerun_required: bool
    artifacts: dict[str, str]
    notes: list[str]
    success: bool

    def to_dict(self) -> dict[str, Any]:
        return {field.name: _to_jsonable(getattr(self, field.name)) for field in fields(self)}

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "StageResult":
        return cls(
            stage=data["stage"],
            changed=data["changed"],
            changed_files=list(data["changed_files"]),
            rerun_required=data["rerun_required"],
            artifacts=dict(data["artifacts"]),
            notes=list(data["notes"]),
            success=data["success"],
        )


@dataclass
class PipelineState:
    config: RunConfig
    workspace_root: Path
    build_system: Literal["gradle"]
    adapter_name: str
    current_analysis: ReanalyzeResult | None
    stage_history: list[StageResult]
    artifacts_root: Path
    final_patch_manifest: Path | None
    legacy_regression_enabled: bool

    def to_dict(self) -> dict[str, Any]:
        return {
            "config": self.config.to_dict(),
            "workspace_root": str(self.workspace_root),
            "build_system": self.build_system,
            "adapter_name": self.adapter_name,
            "current_analysis": _to_jsonable(self.current_analysis),
            "stage_history": [_to_jsonable(item) for item in self.stage_history],
            "artifacts_root": str(self.artifacts_root),
            "final_patch_manifest": (
                str(self.final_patch_manifest) if self.final_patch_manifest is not None else None
            ),
            "legacy_regression_enabled": self.legacy_regression_enabled,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "PipelineState":
        current_analysis = data["current_analysis"]
        return cls(
            config=RunConfig.from_dict(data["config"]),
            workspace_root=Path(data["workspace_root"]),
            build_system=data["build_system"],
            adapter_name=data["adapter_name"],
            current_analysis=(
                ReanalyzeResult.from_dict(current_analysis) if current_analysis is not None else None
            ),
            stage_history=[StageResult.from_dict(item) for item in data["stage_history"]],
            artifacts_root=Path(data["artifacts_root"]),
            final_patch_manifest=_convert_path(data["final_patch_manifest"]),
            legacy_regression_enabled=data["legacy_regression_enabled"],
        )
