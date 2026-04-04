from __future__ import annotations

from pathlib import Path

from arodnap.contracts import RunConfig, StageResult

from .base import (
    BaseNormalizedPatchStageWrapper,
    StageExecutionError,
)

_STAGE_NAME = "close_injector"
_RAW_PATCH_NAME = "java-parser-AutoCloseInjector.patch"


class CloseInjectorStageWrapper(BaseNormalizedPatchStageWrapper):
    name = _STAGE_NAME
    patch_filename = "close_injector.patch"
    raw_patch_filename = _RAW_PATCH_NAME
    command_log_title = "close_injector_tool"

    def build_command(
        self,
        *,
        config: RunConfig,
        workspace_root: Path,
        diagnostics_path: Path,
    ) -> list[str]:
        return [
            "java",
            "-jar",
            str(config.close_injector_jar.resolve()),
            str(diagnostics_path),
            str(workspace_root),
        ]

    def diagnostics_label(self) -> str:
        return "close injector"

    def tool_failure_message(self, *, workspace_root: Path, log_path: Path) -> str:
        return f"Close injector failed for {workspace_root}. See log: {log_path}"

    def patch_apply_failure_message(self, *, log_path: Path) -> str:
        return f"Failed to apply normalized close-injector patch. See log: {log_path}"

    def no_patch_note(self) -> str:
        return "No close-injector patch was generated."

    def changed_note(self, *, changed_files: list[str]) -> str:
        return f"Applied close-injector patch affecting {len(changed_files)} file(s)."


_WRAPPER = CloseInjectorStageWrapper()


def run_close_injector_stage(
    config: RunConfig,
    *,
    workspace_root: Path,
    diagnostics_path: Path,
    stage_output_dir: Path,
) -> StageResult:
    return _WRAPPER.run(
        config=config,
        workspace_root=workspace_root,
        diagnostics_path=diagnostics_path,
        stage_output_dir=stage_output_dir,
    )


__all__ = ["CloseInjectorStageWrapper", "StageExecutionError", "run_close_injector_stage"]
