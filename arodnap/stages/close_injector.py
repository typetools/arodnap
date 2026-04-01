from __future__ import annotations

from pathlib import Path
import subprocess

from arodnap.contracts import RunConfig, StageResult
from arodnap.patch_tool import append_patch_execution_log

from .base import (
    StageExecutionError,
    StagePaths,
    apply_normalized_patch,
    append_command_log,
    normalize_unified_diff_paths,
    write_stage_result,
)

_STAGE_NAME = "close_injector"
_RAW_PATCH_NAME = "java-parser-AutoCloseInjector.patch"


def run_close_injector_stage(
    config: RunConfig,
    *,
    workspace_root: Path,
    diagnostics_path: Path,
    stage_output_dir: Path,
) -> StageResult:
    workspace_root = workspace_root.resolve()
    diagnostics_path = diagnostics_path.resolve()
    if not diagnostics_path.is_file():
        raise StageExecutionError(f"Missing diagnostics file for close injector: {diagnostics_path}")

    stage_paths = StagePaths.for_stage(stage_output_dir, patch_filename="close_injector.patch")
    stage_paths.ensure()
    raw_patch_path = workspace_root / "src" / _RAW_PATCH_NAME
    if raw_patch_path.exists():
        raw_patch_path.unlink()

    command = [
        "java",
        "-jar",
        str(config.close_injector_jar.resolve()),
        str(diagnostics_path),
        str(workspace_root),
    ]
    completed = subprocess.run(
        command,
        cwd=workspace_root,
        capture_output=True,
        text=True,
        check=False,
    )
    append_command_log(stage_paths.log_path, title="close_injector_tool", command=command, completed=completed)

    if completed.returncode != 0:
        raise StageExecutionError(
            f"Close injector failed for {workspace_root}. See log: {stage_paths.log_path}"
        )

    if not raw_patch_path.exists() or raw_patch_path.stat().st_size == 0:
        result = StageResult(
            stage=_STAGE_NAME,
            changed=False,
            changed_files=[],
            rerun_required=False,
            artifacts={"log": str(stage_paths.log_path)},
            notes=["No close-injector patch was generated."],
            success=True,
        )
        write_stage_result(stage_paths.root, result)
        return result

    normalized_patch, changed_files = normalize_unified_diff_paths(
        raw_patch_path.read_text(),
        workspace_root=workspace_root,
    )
    stage_paths.patch_path.write_text(normalized_patch)
    raw_patch_path.unlink(missing_ok=True)

    apply_completed = apply_normalized_patch(workspace_root=workspace_root, patch_path=stage_paths.patch_path)
    append_patch_execution_log(
        stage_paths.log_path,
        title="apply_normalized_patch",
        execution=apply_completed,
    )

    if apply_completed.completed.returncode != 0:
        raise StageExecutionError(
            f"Failed to apply normalized close-injector patch. See log: {stage_paths.log_path}"
        )

    result = StageResult(
        stage=_STAGE_NAME,
        changed=True,
        changed_files=changed_files,
        rerun_required=True,
        artifacts={
            "log": str(stage_paths.log_path),
            "patch": str(stage_paths.patch_path),
        },
        notes=[f"Applied close-injector patch affecting {len(changed_files)} file(s)."],
        success=True,
    )
    write_stage_result(stage_paths.root, result)
    return result


__all__ = ["StageExecutionError", "run_close_injector_stage"]
