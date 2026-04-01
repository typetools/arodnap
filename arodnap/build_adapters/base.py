from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


class UnsupportedProjectError(RuntimeError):
    pass


class MissingBuildToolError(RuntimeError):
    pass


class AdapterExecutionError(RuntimeError):
    pass


@dataclass(frozen=True)
class GradleProject:
    repo_root: Path
    build_file: Path
    build_tool: tuple[str, ...]
    compile_target: str
    source_root: Path
    compiled_classes_root: Path
