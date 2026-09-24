from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from arodnap.build_adapters import (
    AdapterExecutionError,
    MissingBuildToolError,
    UnsupportedProjectError,
    select_build_adapter,
)
from arodnap.build_adapters.base import BuildAdapterContract, ProjectModel
from arodnap.contracts import ReanalyzeResult, RunConfig
from arodnap.orchestrator.results import OutputLayout

from .rlc_runner import RlcRunError, run_resource_leak_checker
from .wpi_runner import WpiRunError, run_wpi


class ReanalyzeError(RuntimeError):
    pass


@dataclass(frozen=True)
class CapturedBuild:
    """The project's build, captured once and reused by every analysis point of a run.

    Repair stages only edit existing source files, so the source list and classpath stay valid;
    each analysis recompiles the current sources instead of re-running the build.
    """

    adapter: BuildAdapterContract
    project: ProjectModel


def capture_build(config: RunConfig, *, workspace_root: Path) -> CapturedBuild:
    """Run the project's build once with the capture hooks installed."""
    try:
        adapter = select_build_adapter(
            workspace_root.resolve(),
            compile_target=config.compile_target,
            build_args=config.build_args,
            build_command=config.build_command,
            timeouts=config.timeouts,
        )
        project = adapter.inspect()
        adapter.validate_compile(project)
    except (AdapterExecutionError, MissingBuildToolError, UnsupportedProjectError) as exc:
        raise ReanalyzeError(str(exc)) from exc
    return CapturedBuild(adapter=adapter, project=project)


def reanalyze(
    config: RunConfig,
    *,
    workspace_root: Path,
    label: str,
    artifacts_root: Path,
    captured: CapturedBuild | None = None,
) -> ReanalyzeResult:
    workspace_root = workspace_root.resolve()
    output_layout = OutputLayout.from_root(artifacts_root)
    output_layout.ensure()
    analysis_paths = output_layout.analysis_paths(label)
    analysis_paths.ensure()

    if captured is None:
        captured = capture_build(config, workspace_root=workspace_root)
    adapter, project = captured.adapter, captured.project
    try:
        release, encoding = adapter.java_language(project)
        source_files_file = adapter.write_source_files_file(project, analysis_paths.source_files_file)
        app_classes_file = adapter.write_app_classes_file(project, analysis_paths.app_classes_file)
        classpath_entries_file = adapter.write_classpath_entries_file(
            project,
            analysis_paths.classpath_entries_file,
        )
        adapter_metadata_path = adapter.write_adapter_metadata_file(
            project,
            source_files_file=source_files_file,
            app_classes_file=app_classes_file,
            classpath_entries_file=classpath_entries_file,
            output_path=analysis_paths.adapter_metadata_path,
        )
        wpi_result = run_wpi(
            config,
            workspace_root=workspace_root,
            source_files_file=source_files_file,
            classpath_entries_file=classpath_entries_file,
            log_path=analysis_paths.wpi_log_path,
            inference_root=analysis_paths.inference_dir,
            release=release,
            encoding=encoding,
        )
        rlc_result = run_resource_leak_checker(
            config,
            workspace_root=workspace_root,
            source_files_file=source_files_file,
            classpath_entries_file=classpath_entries_file,
            inference_dir=wpi_result.inference_dir,
            diagnostics_path=analysis_paths.diagnostics_path,
            release=release,
            encoding=encoding,
        )
    except (
        AdapterExecutionError,
        MissingBuildToolError,
        RlcRunError,
        UnsupportedProjectError,
        WpiRunError,
    ) as exc:
        raise ReanalyzeError(str(exc)) from exc

    return ReanalyzeResult(
        workspace_root=workspace_root,
        label=label,
        wpi_log_path=wpi_result.log_path,
        inference_dir=wpi_result.inference_dir,
        diagnostics_path=rlc_result.diagnostics_path,
        warning_count=rlc_result.warning_count,
        source_files_file=source_files_file,
        app_classes_file=app_classes_file,
        classpath_entries_file=classpath_entries_file,
        adapter_metadata_path=adapter_metadata_path,
        release=release,
        encoding=encoding,
        inference_notes=tuple(
            f"Whole-program inference could not cover {Path(ajava).name.split('-')[0]}: the Checker "
            f"Framework failed to write {ajava} (see {wpi_result.log_path})."
            for ajava in wpi_result.incomplete
        ),
    )
