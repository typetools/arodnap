from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import subprocess
from typing import Mapping, Sequence

from .errors import CommandExecutionError


@dataclass(frozen=True)
class CommandResult:
    command: tuple[str, ...]
    cwd: Path | None
    returncode: int
    stdout: str
    stderr: str


def run_command(
    command: Sequence[str],
    *,
    cwd: Path | None = None,
    env: Mapping[str, str] | None = None,
) -> CommandResult:
    rendered_command = tuple(str(part) for part in command)
    resolved_cwd = cwd.resolve() if cwd is not None else None
    env_dict = dict(env) if env is not None else None

    try:
        completed = subprocess.run(
            list(rendered_command),
            cwd=resolved_cwd,
            env=env_dict,
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError as exc:
        raise CommandExecutionError(
            command=rendered_command,
            cwd=resolved_cwd,
            cause=exc,
        ) from exc

    return CommandResult(
        command=rendered_command,
        cwd=resolved_cwd,
        returncode=completed.returncode,
        stdout=completed.stdout,
        stderr=completed.stderr,
    )


def render_command_log(
    result: CommandResult,
    *,
    tool_name: str | None = None,
    timeout_seconds: int | None = None,
) -> str:
    sections: list[str] = []
    if tool_name is not None:
        sections.append(f"TOOL: {tool_name}")
    if result.cwd is not None:
        sections.append(f"CWD: {result.cwd}")
    if timeout_seconds is not None:
        sections.append(f"TIMEOUT_SECONDS: {timeout_seconds}")
    sections.extend(
        [
            f"COMMAND: {' '.join(result.command)}",
            f"EXIT_CODE: {result.returncode}",
            "STDOUT:",
            result.stdout,
            "STDERR:",
            result.stderr,
        ]
    )
    return "\n".join(sections)
