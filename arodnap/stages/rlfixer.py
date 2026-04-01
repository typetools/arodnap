from __future__ import annotations

import json
import shutil
from pathlib import Path
import subprocess
import sys

from arodnap.contracts import StageResult

from .base import StageExecutionError, append_command_log, write_stage_result

_STAGE_NAME = "rlfixer"


def run_rlfixer_stage(
    *,
    workspace_root: Path,
    diagnostics_path: Path,
    inference_dir: Path,
    compatibility_bundle_root: Path,
    stage_output_dir: Path,
) -> StageResult:
    workspace_root = workspace_root.resolve()
    diagnostics_path = diagnostics_path.resolve()
    inference_dir = inference_dir.resolve()
    compatibility_bundle_root = _validate_compatibility_bundle(compatibility_bundle_root)

    if not workspace_root.is_dir():
        raise StageExecutionError(f"Missing workspace root for RLFixer stage: {workspace_root}")
    if not diagnostics_path.is_file():
        raise StageExecutionError(f"Missing diagnostics file for RLFixer stage: {diagnostics_path}")
    if not inference_dir.is_dir():
        raise StageExecutionError(f"Missing inference directory for RLFixer stage: {inference_dir}")

    stage_output_dir = stage_output_dir.resolve()
    stage_output_dir.mkdir(parents=True, exist_ok=True)
    compatibility_bundle_root = _stage_compatibility_bundle(
        compatibility_bundle_root=compatibility_bundle_root,
        stage_output_dir=stage_output_dir,
    )
    log_path = stage_output_dir / "stage.log"
    fixes_path = stage_output_dir / "fixes.txt"
    debug_path = stage_output_dir / "debug.txt"
    runner_output_dir = stage_output_dir / "_runner_output"
    runner_debug_dir = stage_output_dir / "_runner_debug"
    _reset_directory(runner_output_dir)
    _reset_directory(runner_debug_dir)

    results_file = stage_output_dir / "compat_bundle.txt"
    shutil.copyfile(diagnostics_path, results_file)

    command = [
        sys.executable,
        str(_repo_root() / "RLFixerRunner.py"),
        "--tool",
        "checkerframework",
        "--results",
        str(results_file),
        "--benchmarks",
        str(stage_output_dir),
        "--output",
        str(runner_output_dir),
        "--debug_output",
        str(runner_debug_dir),
        "--wpioutdir",
        str(inference_dir),
    ]
    completed = subprocess.run(
        command,
        cwd=_repo_root(),
        capture_output=True,
        text=True,
        check=False,
    )
    append_command_log(log_path, title="rlfixer_runner", command=command, completed=completed)

    if completed.returncode != 0:
        raise StageExecutionError(f"RLFixer stage failed. See log: {log_path}")

    runner_fixes_path = runner_output_dir / "compat_bundle.txt"
    runner_debug_path = runner_debug_dir / "compat_bundle.txt"
    if runner_fixes_path.exists():
        shutil.move(str(runner_fixes_path), fixes_path)
    else:
        fixes_path.write_text("")
    if runner_debug_path.exists():
        shutil.move(str(runner_debug_path), debug_path)
    else:
        debug_path.write_text("")

    result = StageResult(
        stage=_STAGE_NAME,
        changed=False,
        changed_files=[],
        rerun_required=False,
        artifacts={
            "log": str(log_path),
            "fixes": str(fixes_path),
            "debug": str(debug_path),
            "compatibility_bundle_metadata": str(compatibility_bundle_root / "metadata.json"),
        },
        notes=["RLFixer completed without mutating workspace sources."],
        success=True,
    )
    write_stage_result(stage_output_dir, result)
    return result


def _validate_compatibility_bundle(bundle_root: Path) -> Path:
    bundle_root = bundle_root.resolve()
    if not bundle_root.is_dir():
        raise StageExecutionError(f"Missing RLFixer compatibility bundle root: {bundle_root}")

    info_dir = bundle_root / "info"
    classes_file = info_dir / "classes"
    sources_file = info_dir / "sources"
    metadata_path = bundle_root / "metadata.json"
    jar_dir = bundle_root / "jarfile"

    for required_file in (classes_file, sources_file, metadata_path):
        if not required_file.is_file():
            raise StageExecutionError(f"Missing RLFixer compatibility bundle file: {required_file}")

    if not jar_dir.is_dir():
        raise StageExecutionError(f"Missing RLFixer compatibility jar directory: {jar_dir}")

    jar_files = sorted(path for path in jar_dir.iterdir() if path.is_file() and path.suffix == ".jar")
    if len(jar_files) != 1:
        raise StageExecutionError(
            f"RLFixer compatibility bundle must contain exactly one jar file in {jar_dir}"
        )

    try:
        metadata = json.loads(metadata_path.read_text())
    except json.JSONDecodeError as exc:
        raise StageExecutionError(
            f"Malformed RLFixer compatibility bundle metadata: {metadata_path}"
        ) from exc
    if not isinstance(metadata, dict):
        raise StageExecutionError(
            f"Malformed RLFixer compatibility bundle metadata: {metadata_path}"
        )

    return bundle_root


def _stage_compatibility_bundle(*, compatibility_bundle_root: Path, stage_output_dir: Path) -> Path:
    staged_root = (stage_output_dir / "compat_bundle").resolve()
    if staged_root == compatibility_bundle_root:
        return staged_root

    if staged_root.exists():
        shutil.rmtree(staged_root)
    shutil.copytree(compatibility_bundle_root, staged_root)
    return staged_root


def _reset_directory(path: Path) -> None:
    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True, exist_ok=True)


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


__all__ = ["StageExecutionError", "run_rlfixer_stage"]
