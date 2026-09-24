from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path
import signal
import subprocess
from typing import Mapping, Sequence

from .errors import CommandExecutionError, CommandTimeoutError


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
    timeout_seconds: int | None = None,
) -> CommandResult:
    """Run a command to completion and capture its output.

    Without `timeout_seconds` there is no limit. With one, a command that runs longer is
    killed together with every process it started (builds leave daemons and compiler
    workers behind), and CommandTimeoutError is raised.
    """
    rendered_command = tuple(str(part) for part in command)
    resolved_cwd = cwd.resolve() if cwd is not None else None
    env_dict = dict(env) if env is not None else None

    if timeout_seconds is None:
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
            raise CommandExecutionError(command=rendered_command, cwd=resolved_cwd, cause=exc) from exc
        return CommandResult(
            command=rendered_command,
            cwd=resolved_cwd,
            returncode=completed.returncode,
            stdout=completed.stdout,
            stderr=completed.stderr,
        )

    try:
        process = subprocess.Popen(
            list(rendered_command),
            cwd=resolved_cwd,
            env=env_dict,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            # Its own process group, so a timeout can kill the whole tree.
            start_new_session=os.name == "posix",
        )
    except OSError as exc:
        raise CommandExecutionError(
            command=rendered_command,
            cwd=resolved_cwd,
            cause=exc,
        ) from exc

    try:
        stdout, stderr = process.communicate(timeout=timeout_seconds)
    except subprocess.TimeoutExpired:
        _kill_tree(process)
        stdout, stderr = process.communicate()
        raise CommandTimeoutError(
            command=rendered_command,
            cwd=resolved_cwd,
            timeout_seconds=timeout_seconds,
            stdout=stdout or "",
            stderr=stderr or "",
        ) from None
    except BaseException:
        # Ctrl-C or any other interruption: do not leave the command running.
        _kill_tree(process)
        process.wait()
        raise

    return CommandResult(
        command=rendered_command,
        cwd=resolved_cwd,
        returncode=process.returncode,
        stdout=stdout,
        stderr=stderr,
    )


def _kill_tree(process: subprocess.Popen) -> None:
    if process.poll() is not None:
        return
    if os.name == "posix":
        try:
            if os.getpgid(process.pid) == process.pid:
                os.killpg(process.pid, signal.SIGKILL)
                return
        except (ProcessLookupError, PermissionError):
            return
    process.kill()


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
