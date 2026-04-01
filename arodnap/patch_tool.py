from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import shutil
import subprocess
from typing import Literal


PatchFlavor = Literal["gnu", "bsd", "unknown"]


class PatchToolError(RuntimeError):
    """Raised when no compatible patch binary is available."""


@dataclass(frozen=True)
class PatchTool:
    binary: str
    flavor: PatchFlavor
    version: str


@dataclass(frozen=True)
class PatchExecution:
    tool: PatchTool
    command: list[str]
    completed: subprocess.CompletedProcess[str]


def discover_patch_tool(*, require_gnu: bool, operation_label: str) -> PatchTool:
    discovered: list[PatchTool] = []
    for candidate in ("gpatch", "patch"):
        tool = _probe_patch_tool(candidate)
        if tool is None:
            continue
        discovered.append(tool)
        if tool.flavor == "gnu":
            return tool

    if require_gnu:
        raise PatchToolError(_gnu_requirement_error(operation_label=operation_label, discovered=discovered))

    for tool in discovered:
        if tool.flavor in {"gnu", "bsd"}:
            return tool

    if not discovered:
        raise PatchToolError(
            f"No patch binary was found for {operation_label}. Install GNU patch as 'gpatch' or 'patch'."
        )

    raise PatchToolError(
        f"Unsupported patch implementation for {operation_label}: {discovered[0].binary} ({discovered[0].version})"
    )


def run_patch(
    *,
    cwd: Path,
    patch_path: Path,
    strip_level: int,
    check_only: bool,
    require_gnu: bool,
    operation_label: str,
    extra_args: list[str] | None = None,
    forward: bool = False,
    ignore_whitespace: bool = False,
) -> PatchExecution:
    tool = discover_patch_tool(require_gnu=require_gnu, operation_label=operation_label)
    command = [tool.binary]
    if check_only:
        command.append("--dry-run" if tool.flavor == "gnu" else "-C")
    if forward:
        command.append("--forward")
    command.extend(["-p", str(strip_level), "-u"])
    command.extend(extra_args or [])
    if ignore_whitespace:
        command.append("--ignore-whitespace")
    command.extend(["-i", str(patch_path)])
    completed = subprocess.run(
        command,
        cwd=cwd,
        capture_output=True,
        text=True,
        check=False,
    )
    return PatchExecution(tool=tool, command=command, completed=completed)


def append_patch_execution_log(log_path: Path, *, title: str, execution: PatchExecution) -> None:
    lines = [
        f"== {title} ==",
        f"PATCH_BINARY: {execution.tool.binary}",
        f"PATCH_FLAVOR: {execution.tool.flavor}",
        f"PATCH_VERSION: {execution.tool.version}",
        f"COMMAND: {' '.join(execution.command)}",
        f"EXIT_CODE: {execution.completed.returncode}",
        "STDOUT:",
        execution.completed.stdout,
        "STDERR:",
        execution.completed.stderr,
        "",
    ]
    with log_path.open("a", encoding="utf-8") as handle:
        handle.write("\n".join(lines))


def _probe_patch_tool(candidate: str) -> PatchTool | None:
    binary = shutil.which(candidate)
    if binary is None:
        return None

    try:
        completed = subprocess.run(
            [binary, "--version"],
            capture_output=True,
            text=True,
            check=False,
        )
    except FileNotFoundError:
        return None

    version_output = "\n".join(part for part in (completed.stdout, completed.stderr) if part).strip()
    version = _first_nonempty_line(version_output) or "<unknown version>"
    flavor = _detect_patch_flavor(version_output or version)
    return PatchTool(binary=binary, flavor=flavor, version=version)


def _detect_patch_flavor(output: str) -> PatchFlavor:
    normalized = output.lower()
    if "gnu patch" in normalized:
        return "gnu"
    if "apple" in normalized or "bsd" in normalized or "freebsd" in normalized:
        return "bsd"
    return "unknown"


def _first_nonempty_line(text: str) -> str:
    for line in text.splitlines():
        if line.strip():
            return line.strip()
    return ""


def _gnu_requirement_error(*, operation_label: str, discovered: list[PatchTool]) -> str:
    if not discovered:
        return (
            f"GNU patch is required for {operation_label}, but no patch binary was found. "
            "Install GNU patch and expose it as 'gpatch' or 'patch'."
        )

    detected = ", ".join(f"{tool.binary} ({tool.version})" for tool in discovered)
    return (
        f"GNU patch is required for {operation_label}, but no GNU-compatible patch binary was found. "
        f"Detected: {detected}. Install GNU patch and expose it as 'gpatch' or 'patch'."
    )


__all__ = [
    "PatchExecution",
    "PatchTool",
    "PatchToolError",
    "append_patch_execution_log",
    "discover_patch_tool",
    "run_patch",
]
