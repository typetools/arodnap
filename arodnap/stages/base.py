from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
import subprocess

from arodnap.contracts import StageResult


class StageExecutionError(RuntimeError):
    """Raised when a stage tool fails or its outputs cannot be normalized."""


class StageNotImplementedError(NotImplementedError):
    """Raised by placeholder stage modules before wrapper implementation."""


@dataclass(frozen=True)
class StagePaths:
    root: Path
    log_path: Path
    patch_path: Path
    stage_result_path: Path

    @classmethod
    def for_stage(cls, stage_output_dir: Path, *, patch_filename: str) -> "StagePaths":
        root = stage_output_dir.resolve()
        return cls(
            root=root,
            log_path=root / "stage.log",
            patch_path=root / patch_filename,
            stage_result_path=root / "stage_result.json",
        )

    def ensure(self) -> None:
        self.root.mkdir(parents=True, exist_ok=True)


def append_command_log(
    log_path: Path,
    *,
    title: str,
    command: list[str],
    completed: subprocess.CompletedProcess[str],
) -> None:
    lines = [
        f"== {title} ==",
        f"COMMAND: {' '.join(command)}",
        f"EXIT_CODE: {completed.returncode}",
        "STDOUT:",
        completed.stdout,
        "STDERR:",
        completed.stderr,
        "",
    ]
    with log_path.open("a", encoding="utf-8") as handle:
        handle.write("\n".join(lines))


def apply_normalized_patch(
    *,
    workspace_root: Path,
    patch_path: Path,
    extra_args: list[str] | None = None,
) -> subprocess.CompletedProcess[str]:
    command = [
        "patch",
        "--forward",
        "-p0",
        "-u",
        *(extra_args or []),
        "--ignore-whitespace",
        "-i",
        str(patch_path),
    ]
    return subprocess.run(
        command,
        cwd=workspace_root,
        capture_output=True,
        text=True,
        check=False,
    )


def write_stage_result(stage_output_dir: Path, result: StageResult) -> Path:
    stage_result_path = stage_output_dir.resolve() / "stage_result.json"
    stage_result_path.parent.mkdir(parents=True, exist_ok=True)
    stage_result_path.write_text(json.dumps(result.to_dict(), indent=2, sort_keys=True) + "\n")
    return stage_result_path


def normalize_unified_diff_paths(patch_text: str, *, workspace_root: Path) -> tuple[str, list[str]]:
    workspace_root = workspace_root.resolve()
    normalized_lines: list[str] = []
    changed_files: list[str] = []
    seen_files: set[str] = set()
    saw_header = False

    for line in patch_text.replace("\r\n", "\n").splitlines():
        if line.startswith(("--- ", "+++ ")):
            marker, remainder = line[:4], line[4:]
            path_text, separator, suffix = remainder.partition("\t")
            normalized_path = _normalize_patch_path(path_text.strip(), workspace_root=workspace_root)
            normalized_lines.append(f"{marker}{normalized_path}{separator}{suffix}")
            if normalized_path != "/dev/null" and normalized_path not in seen_files:
                changed_files.append(normalized_path)
                seen_files.add(normalized_path)
            saw_header = True
            continue
        normalized_lines.append(line)

    if not saw_header:
        raise StageExecutionError("Patch output did not contain unified diff file headers.")
    if not changed_files:
        raise StageExecutionError("Patch output did not reference any repo files.")

    return "\n".join(normalized_lines) + "\n", changed_files


def _normalize_patch_path(path_text: str, *, workspace_root: Path) -> str:
    if path_text == "/dev/null":
        return path_text

    candidate = Path(path_text)
    if candidate.is_absolute():
        try:
            return candidate.resolve().relative_to(workspace_root).as_posix()
        except ValueError as exc:
            raise StageExecutionError(
                f"Patch path {path_text} does not live under workspace root {workspace_root}."
            ) from exc

    relative = Path(path_text.removeprefix("./"))
    if relative.is_absolute() or ".." in relative.parts:
        raise StageExecutionError(f"Unsupported patch path outside workspace root: {path_text}")
    return relative.as_posix()


__all__ = [
    "StageExecutionError",
    "StageNotImplementedError",
    "StagePaths",
    "apply_normalized_patch",
    "append_command_log",
    "normalize_unified_diff_paths",
    "write_stage_result",
]
