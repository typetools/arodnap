from __future__ import annotations

import os
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from arodnap.validation_session import (
    ARODNAP_PYTHON_ENV,
    ARODNAP_VALIDATION_ROOT_ENV,
    GRADLE_USER_HOME_ENV,
    ValidationSession,
)


@dataclass(frozen=True)
class ValidationCommandResult:
    label: str
    command: list[str]
    cwd: Path
    exit_code: int | None
    success: bool
    stdout: str
    stderr: str
    invocation_error: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "label": self.label,
            "command": list(self.command),
            "cwd": str(self.cwd),
            "exit_code": self.exit_code,
            "success": self.success,
            "invocation_error": self.invocation_error,
        }


def build_validation_env(session: ValidationSession, *, env: dict[str, str] | None = None) -> dict[str, str]:
    run_env = dict(os.environ if env is None else env)
    run_env[ARODNAP_PYTHON_ENV] = str(session.python_executable)
    run_env[GRADLE_USER_HOME_ENV] = str(session.gradle_user_home)
    run_env[ARODNAP_VALIDATION_ROOT_ENV] = str(session.validation_root)
    return run_env


def run_validation_command(
    command: list[str],
    *,
    label: str,
    cwd: Path,
    env: dict[str, str] | None = None,
) -> ValidationCommandResult:
    resolved_cwd = cwd.resolve()
    try:
        completed = subprocess.run(
            command,
            cwd=resolved_cwd,
            env=env,
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError as exc:
        return ValidationCommandResult(
            label=label,
            command=list(command),
            cwd=resolved_cwd,
            exit_code=None,
            success=False,
            stdout="",
            stderr="",
            invocation_error=str(exc),
        )

    return ValidationCommandResult(
        label=label,
        command=list(command),
        cwd=resolved_cwd,
        exit_code=completed.returncode,
        success=completed.returncode == 0,
        stdout=completed.stdout,
        stderr=completed.stderr,
        invocation_error=None,
    )


def render_command_failure(prefix: str, result: ValidationCommandResult) -> str:
    sections = [prefix, f"Command: {' '.join(result.command)}"]
    if result.invocation_error is not None:
        sections.append(f"invocation_error:\n{result.invocation_error}")
    if result.stdout.strip():
        sections.append(f"stdout:\n{result.stdout.rstrip()}")
    if result.stderr.strip():
        sections.append(f"stderr:\n{result.stderr.rstrip()}")
    return "\n".join(sections)


def tool_repo_root() -> Path:
    return Path(__file__).resolve().parents[1]
