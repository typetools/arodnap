from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from arodnap.contracts import ReanalyzeResult, StageResult


@dataclass(frozen=True)
class AnalysisOutputPaths:
    label: str
    metadata_dir: Path
    wpi_log_path: Path
    inference_dir: Path
    diagnostics_path: Path
    source_files_file: Path
    app_classes_file: Path
    classpath_entries_file: Path
    adapter_metadata_path: Path

    def ensure(self) -> None:
        self.metadata_dir.mkdir(parents=True, exist_ok=True)
        self.wpi_log_path.parent.mkdir(parents=True, exist_ok=True)
        self.inference_dir.parent.mkdir(parents=True, exist_ok=True)
        self.diagnostics_path.parent.mkdir(parents=True, exist_ok=True)


@dataclass(frozen=True)
class OutputLayout:
    root: Path
    report_path: Path
    manifest_path: Path
    diagnostics_dir: Path
    inference_dir: Path
    logs_dir: Path
    stages_dir: Path
    patches_dir: Path
    patches_manifest_path: Path

    @classmethod
    def from_root(cls, root: Path) -> "OutputLayout":
        resolved_root = root.resolve()
        return cls(
            root=resolved_root,
            report_path=resolved_root / "report.json",
            manifest_path=resolved_root / "manifest.json",
            diagnostics_dir=resolved_root / "diagnostics",
            inference_dir=resolved_root / "inference",
            logs_dir=resolved_root / "logs",
            stages_dir=resolved_root / "stages",
            patches_dir=resolved_root / "patches",
            patches_manifest_path=resolved_root / "patches" / "manifest.json",
        )

    def ensure(self) -> None:
        for directory in (
            self.root,
            self.diagnostics_dir,
            self.inference_dir,
            self.logs_dir,
            self.stages_dir,
            self.patches_dir,
        ):
            directory.mkdir(parents=True, exist_ok=True)

    def analysis_paths(self, label: str) -> AnalysisOutputPaths:
        metadata_dir = self.logs_dir / label
        return AnalysisOutputPaths(
            label=label,
            metadata_dir=metadata_dir,
            wpi_log_path=metadata_dir / "wpi.log",
            inference_dir=self.inference_dir / label,
            diagnostics_path=self.diagnostics_dir / f"{label}.txt",
            source_files_file=metadata_dir / "source-files.txt",
            app_classes_file=metadata_dir / "app-classes.txt",
            classpath_entries_file=metadata_dir / "classpath-entries.txt",
            adapter_metadata_path=metadata_dir / "adapter-metadata.json",
        )


__all__ = [
    "AnalysisOutputPaths",
    "OutputLayout",
    "ReanalyzeResult",
    "StageResult",
]
