from __future__ import annotations

from pathlib import Path

from arodnap.contracts import RunConfig, StageResult
from arodnap.runtime import java_executable

from .base import (
    BaseNormalizedPatchStageWrapper,
    CompileInputs,
    StageExecutionError,
)

_STAGE_NAME = "owning_field"
_RAW_PATCH_NAME = "owning-field.patch"


class OwningFieldStageWrapper(BaseNormalizedPatchStageWrapper):
    name = _STAGE_NAME
    patch_filename = "owning_field.patch"
    raw_patch_filename = _RAW_PATCH_NAME
    command_log_title = "owning_field_tool"

    def build_command(
        self,
        *,
        config: RunConfig,
        workspace_root: Path,
        diagnostics_path: Path,
        java_properties: list[str],
    ) -> list[str]:
        return [
            java_executable(),
            *java_properties,
            "-jar",
            str(config.owning_field_jar.resolve()),
            "--log",
            str(diagnostics_path),
            "--project-root",
            str(workspace_root),
        ]

    def diagnostics_label(self) -> str:
        return "owning field"

    def tool_failure_message(self, *, workspace_root: Path, log_path: Path) -> str:
        return f"Owning field fixer failed for {workspace_root}. See log: {log_path}"

    def patch_apply_failure_message(self, *, log_path: Path) -> str:
        return f"Failed to apply normalized owning-field patch. See log: {log_path}"

    def no_patch_note(self) -> str:
        return "No owning-field patch was generated."

    def changed_note(self, *, changed_files: list[str]) -> str:
        return f"Applied owning-field patch affecting {len(changed_files)} file(s)."

    def patch_apply_fuzz(self) -> int:
        return 3


_WRAPPER = OwningFieldStageWrapper()


def run_owning_field_stage(
    config: RunConfig,
    *,
    workspace_root: Path,
    diagnostics_path: Path,
    stage_output_dir: Path,
    compile_inputs: CompileInputs | None = None,
) -> StageResult:
    return _WRAPPER.run(
        config=config,
        workspace_root=workspace_root,
        diagnostics_path=diagnostics_path,
        stage_output_dir=stage_output_dir,
        compile_inputs=compile_inputs,
    )


__all__ = ["OwningFieldStageWrapper", "StageExecutionError", "run_owning_field_stage"]
