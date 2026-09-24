from __future__ import annotations

from pathlib import Path
from typing import Sequence


class CommandExecutionError(RuntimeError):
    def __init__(
        self,
        *,
        command: Sequence[str],
        cwd: Path | None,
        cause: OSError,
    ) -> None:
        self.command = tuple(command)
        self.cwd = cwd
        self.cause = cause
        location = f" in {cwd}" if cwd is not None else ""
        super().__init__(
            f"Failed to execute command{location}: {' '.join(self.command)} ({cause})"
        )


class CommandTimeoutError(CommandExecutionError):
    """A command ran longer than the limit the user set; it and its children were killed."""

    def __init__(
        self,
        *,
        command: Sequence[str],
        cwd: Path | None,
        timeout_seconds: int,
        stdout: str = "",
        stderr: str = "",
    ) -> None:
        self.command = tuple(command)
        self.cwd = cwd
        self.cause = None
        self.timeout_seconds = timeout_seconds
        self.stdout = stdout
        self.stderr = stderr
        location = f" in {cwd}" if cwd is not None else ""
        RuntimeError.__init__(
            self,
            f"Command timed out after {timeout_seconds} seconds{location}: {' '.join(self.command)}",
        )
