from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


class UnsupportedProjectError(RuntimeError):
    pass


class MissingBuildToolError(RuntimeError):
    pass


@dataclass(frozen=True)
class GradleProject:
    repo_root: Path
    build_file: Path
    build_tool: tuple[str, ...]
    compile_target: str
    source_root: Path
