"""Applies patches for the pipeline and `apply`, with Arodnap's built-in applier
(`arodnap.unified_patch`), so no external `patch` program is needed.

The interface keeps the shape of a command execution (command, exit code, output) so stage logs
and error messages stay the same as when GNU patch was used.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Literal

from arodnap.runtime import CommandResult, render_command_log
from arodnap.unified_patch import apply_patch
from arodnap.version import __version__

PatchFlavor = Literal["builtin"]


class PatchToolError(RuntimeError):
    """Raised when a patch file cannot be read."""


@dataclass(frozen=True)
class PatchTool:
    binary: str
    flavor: PatchFlavor
    version: str


@dataclass(frozen=True)
class PatchExecution:
    tool: PatchTool
    command: list[str]
    completed: CommandResult


BUILTIN = PatchTool(binary="arodnap built-in", flavor="builtin", version=f"arodnap {__version__}")


def discover_patch_tool(*, require_gnu: bool = False, operation_label: str = "") -> PatchTool:
    """The patch applier; always the built-in one (kept for callers that report it)."""
    return BUILTIN


def run_patch(
    *,
    cwd: Path,
    patch_path: Path,
    strip_level: int,
    check_only: bool,
    require_gnu: bool = False,
    operation_label: str = "",
    fuzz: int = 0,
    forward: bool = False,
    ignore_whitespace: bool = False,
) -> PatchExecution:
    """Apply (or with `check_only`, only check) a unified diff under `cwd`.

    `forward` is accepted for compatibility: the applier never reverses a patch, and reports a
    patch whose changes are already present as a failure.
    """
    try:
        # Exact contents: read_text would turn "\r\n" into "\n".
        patch_text = patch_path.read_bytes().decode("utf-8", errors="surrogateescape")
    except OSError as exc:
        raise PatchToolError(f"Cannot read patch {patch_path}: {exc}") from exc
    outcome = apply_patch(
        cwd,
        patch_text,
        strip_level=strip_level,
        check_only=check_only,
        fuzz=fuzz,
        ignore_whitespace=ignore_whitespace,
    )
    command = ["arodnap-patch", f"-p{strip_level}"]
    if check_only:
        command.append("--dry-run")
    if fuzz:
        command.append(f"--fuzz={fuzz}")
    if ignore_whitespace:
        command.append("--ignore-whitespace")
    command.append(str(patch_path))
    completed = CommandResult(
        command=tuple(command),
        cwd=cwd,
        returncode=0 if outcome.ok else 1,
        stdout="".join(f"{message}\n" for message in outcome.messages),
        stderr="".join(f"{error}\n" for error in outcome.errors),
    )
    return PatchExecution(tool=BUILTIN, command=command, completed=completed)


def append_patch_execution_log(log_path: Path, *, title: str, execution: PatchExecution) -> None:
    lines = [
        f"== {title} ==",
        f"PATCH_BINARY: {execution.tool.binary}",
        f"PATCH_FLAVOR: {execution.tool.flavor}",
        f"PATCH_VERSION: {execution.tool.version}",
        render_command_log(execution.completed, tool_name="patch"),
        "",
    ]
    with log_path.open("a", encoding="utf-8") as handle:
        handle.write("\n".join(lines))


__all__ = [
    "PatchExecution",
    "PatchTool",
    "PatchToolError",
    "append_patch_execution_log",
    "discover_patch_tool",
    "run_patch",
]
