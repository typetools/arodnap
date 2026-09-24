from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Protocol, runtime_checkable


class UnsupportedProjectError(RuntimeError):
    pass


class MissingBuildToolError(RuntimeError):
    pass


class AdapterExecutionError(RuntimeError):
    pass


@dataclass(frozen=True)
class BuildToolSelection:
    build_system: str
    adapter_name: str


@dataclass(frozen=True)
class ProjectModel:
    repo_root: Path
    build_file: Path
    build_system: str
    adapter_name: str
    build_tool: tuple[str, ...]
    build_tool_source: str
    compile_target: str
    source_root: Path
    compiled_classes_root: Path


GradleProject = ProjectModel


@dataclass(frozen=True)
class AdapterMetadata:
    repo_root: Path
    build_file: Path
    build_system: str
    adapter_name: str
    build_tool: tuple[str, ...]
    build_tool_source: str
    compile_target: str
    source_root: Path
    compiled_classes_root: Path
    source_files_file: Path
    app_classes_file: Path
    classpath_entries_file: Path

    def to_payload(self) -> dict[str, object]:
        return {
            "repo_root": str(self.repo_root),
            "build_file": str(self.build_file),
            "build_system": self.build_system,
            "adapter_name": self.adapter_name,
            "build_tool": list(self.build_tool),
            "build_tool_source": self.build_tool_source,
            "compile_target": self.compile_target,
            "source_root": str(self.source_root),
            "compiled_classes_root": str(self.compiled_classes_root),
            "source_files_file": str(self.source_files_file),
            "app_classes_file": str(self.app_classes_file),
            "classpath_entries_file": str(self.classpath_entries_file),
        }


@runtime_checkable
class BuildAdapterContract(Protocol):
    adapter_name: str
    build_system: str

    def detect(self) -> BuildToolSelection:
        ...

    def inspect(self) -> ProjectModel:
        ...

    def validate_compile(self, project: ProjectModel) -> None:
        ...

    def java_language(self, project: ProjectModel) -> tuple[int | None, str | None]:
        """The build's Java release level and source encoding (None when the build sets none)."""
        ...

    def write_source_files_file(self, project: ProjectModel, output_path: Path) -> Path:
        ...

    def write_app_classes_file(self, project: ProjectModel, output_path: Path) -> Path:
        ...

    def write_classpath_entries_file(self, project: ProjectModel, output_path: Path) -> Path:
        ...

    def write_adapter_metadata_file(
        self,
        project: ProjectModel,
        *,
        source_files_file: Path,
        app_classes_file: Path,
        classpath_entries_file: Path,
        output_path: Path,
    ) -> Path:
        ...


def build_tool_source(build_tool: tuple[str, ...]) -> str:
    if build_tool and build_tool[0] == "./gradlew":
        return "wrapper"
    return "system"


__all__ = [
    "AdapterExecutionError",
    "AdapterMetadata",
    "BuildAdapterContract",
    "BuildToolSelection",
    "GradleProject",
    "MissingBuildToolError",
    "ProjectModel",
    "UnsupportedProjectError",
    "build_tool_source",
]
