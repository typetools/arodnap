from __future__ import annotations

import shutil
from pathlib import Path

from arodnap.build_adapters import (
    AdapterExecutionError,
    MissingBuildToolError,
    UnsupportedProjectError,
    select_build_adapter,
)
from arodnap.contracts import ReanalyzeResult, RunConfig
from arodnap.orchestrator.results import OutputLayout

from .rlc_runner import RlcRunError, run_resource_leak_checker


class AnalyzeError(RuntimeError):
    pass


def analyze_once(
    config: RunConfig,
    *,
    workspace_root: Path,
    label: str,
    artifacts_root: Path,
) -> ReanalyzeResult:
    workspace_root = workspace_root.resolve()
    output_layout = OutputLayout.from_root(artifacts_root)
    output_layout.ensure()
    analysis_paths = output_layout.analysis_paths(label)
    analysis_paths.ensure()

    try:
        adapter = select_build_adapter(
            workspace_root,
            compile_target=config.compile_target,
            build_args=config.build_args,
            build_command=config.build_command,
            timeouts=config.timeouts,
        )
        project = adapter.inspect()
        adapter.validate_compile(project)
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
        analysis_paths.wpi_log_path.write_text("SKIPPED: analyze does not run WPI.\n")
        _reset_directory(analysis_paths.inference_dir)
        rlc_result = run_resource_leak_checker(
            config,
            workspace_root=workspace_root,
            source_files_file=source_files_file,
            classpath_entries_file=classpath_entries_file,
            inference_dir=None,
            diagnostics_path=analysis_paths.diagnostics_path,
            release=release,
            encoding=encoding,
        )
    except (
        AdapterExecutionError,
        MissingBuildToolError,
        RlcRunError,
        UnsupportedProjectError,
    ) as exc:
        raise AnalyzeError(str(exc)) from exc

    return ReanalyzeResult(
        workspace_root=workspace_root,
        label=label,
        wpi_log_path=analysis_paths.wpi_log_path.resolve(),
        inference_dir=analysis_paths.inference_dir.resolve(),
        diagnostics_path=rlc_result.diagnostics_path,
        warning_count=rlc_result.warning_count,
        source_files_file=source_files_file,
        app_classes_file=app_classes_file,
        classpath_entries_file=classpath_entries_file,
        adapter_metadata_path=adapter_metadata_path,
        release=release,
        encoding=encoding,
    )


def _reset_directory(path: Path) -> None:
    path = path.resolve()
    if path.exists():
        if path.is_dir():
            shutil.rmtree(path)
        else:
            path.unlink()
    path.mkdir(parents=True, exist_ok=True)
