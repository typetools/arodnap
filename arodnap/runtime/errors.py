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
