from __future__ import annotations

from datetime import datetime, timezone
from dataclasses import dataclass
from pathlib import Path
import time

from arodnap.apply_support import apply_patch_bundle
from arodnap.analysis.analyze import analyze_once
from arodnap.analysis.reanalyze import reanalyze
from arodnap.build_adapters import default_build_tool_selection, select_build_adapter, UnsupportedProjectError
from arodnap.contracts import PipelineState, ReanalyzeResult, RunConfig, StageResult
from arodnap.orchestrator.results import OutputLayout, write_report, write_run_manifest
from arodnap.orchestrator.workspace import copied_workspace
from arodnap.stages.registry import REPAIR_STAGE_REGISTRY, StageRunInput


def run_analyze(config: RunConfig) -> int:
    return _run_analysis_command(config, analysis_runner=analyze_once)


def run_infer(config: RunConfig) -> int:
    return _run_analysis_command(config, analysis_runner=reanalyze)


def run_repair(config: RunConfig) -> int:
    output_layout = OutputLayout.from_root(config.out_dir)
    output_layout.ensure()
    run_started_at = _timestamp_now()
    run_started_perf = time.perf_counter()
    analysis_runs: list[dict[str, object]] = []
    stage_timings: list[dict[str, object]] = []

    with copied_workspace(config.repo_root, keep_workspace=config.keep_workspace) as workspace:
        state = _initial_state(config, workspace_root=workspace.workspace_root, artifacts_root=output_layout.root)

        try:
            current_analysis = _run_timed_analysis(
                analysis_runs,
                label="initial",
                runner=lambda: reanalyze(
                    config,
                    workspace_root=workspace.workspace_root,
                    label="initial",
                    artifacts_root=output_layout.root,
                ),
            )
            state.current_analysis = current_analysis
            repair_state = _RepairExecutionState(current_analysis=current_analysis)

            for stage_definition in REPAIR_STAGE_REGISTRY:
                stage_input = StageRunInput(
                    config=config,
                    workspace_root=workspace.workspace_root,
                    output_layout=output_layout,
                    current_analysis=repair_state.current_analysis,
                    rlfixer_result=repair_state.rlfixer_result,
                    prior_results=tuple(state.stage_history),
                )
                stage_result = _run_timed_stage(
                    stage_timings,
                    stage_name=stage_definition.name,
                    runner=lambda sd=stage_definition, si=stage_input: sd.runner(si),
                )
                state.stage_history.append(stage_result)

                if stage_definition.captures_rlfixer_result:
                    repair_state.rlfixer_result = stage_result
                if stage_definition.promotes_patch_manifest:
                    state.final_patch_manifest = output_layout.promote_patch_manifest(
                        Path(stage_result.artifacts["patch_manifest"])
                    )

                if stage_definition.rerun_analysis_label and stage_result.rerun_required:
                    repair_state.current_analysis = _run_timed_analysis(
                        analysis_runs,
                        label=stage_definition.rerun_analysis_label,
                        runner=lambda stage_definition=stage_definition: reanalyze(
                            config,
                            workspace_root=workspace.workspace_root,
                            label=stage_definition.rerun_analysis_label,
                            artifacts_root=output_layout.root,
                        ),
                    )
                    state.current_analysis = repair_state.current_analysis

            _write_run_outputs(
                output_layout=output_layout,
                state=state,
                success=True,
                run_started_at=run_started_at,
                run_started_perf=run_started_perf,
                analysis_runs=analysis_runs,
                stage_timings=stage_timings,
            )
        except Exception as exc:
            _write_run_outputs(
                output_layout=output_layout,
                state=state,
                success=False,
                error=str(exc),
                error_type=type(exc).__name__,
                run_started_at=run_started_at,
                run_started_perf=run_started_perf,
                analysis_runs=analysis_runs,
                stage_timings=stage_timings,
            )
            raise

    return 0


def run_apply(config: RunConfig) -> int:
    apply_patch_bundle(config)
    return 0


def _run_analysis_command(
    config: RunConfig,
    *,
    analysis_runner,
) -> int:
    output_layout = OutputLayout.from_root(config.out_dir)
    output_layout.ensure()
    run_started_at = _timestamp_now()
    run_started_perf = time.perf_counter()
    analysis_runs: list[dict[str, object]] = []

    with copied_workspace(config.repo_root, keep_workspace=config.keep_workspace) as workspace:
        state = _initial_state(config, workspace_root=workspace.workspace_root, artifacts_root=output_layout.root)

        try:
            state.current_analysis = _run_timed_analysis(
                analysis_runs,
                label="initial",
                runner=lambda: analysis_runner(
                    config,
                    workspace_root=workspace.workspace_root,
                    label="initial",
                    artifacts_root=output_layout.root,
                ),
            )
            _write_run_outputs(
                output_layout=output_layout,
                state=state,
                success=True,
                run_started_at=run_started_at,
                run_started_perf=run_started_perf,
                analysis_runs=analysis_runs,
                stage_timings=[],
            )
        except Exception as exc:
            _write_run_outputs(
                output_layout=output_layout,
                state=state,
                success=False,
                error=str(exc),
                error_type=type(exc).__name__,
                run_started_at=run_started_at,
                run_started_perf=run_started_perf,
                analysis_runs=analysis_runs,
                stage_timings=[],
            )
            raise

    return 0


def _write_run_outputs(
    *,
    output_layout: OutputLayout,
    state: PipelineState,
    success: bool,
    run_started_at: str,
    run_started_perf: float,
    analysis_runs: list[dict[str, object]],
    stage_timings: list[dict[str, object]],
    error: str | None = None,
    error_type: str | None = None,
) -> None:
    run_metadata = _build_run_metadata(
        state=state,
        output_layout=output_layout,
        run_started_at=run_started_at,
        run_completed_at=_timestamp_now(),
        run_elapsed_seconds=_elapsed_seconds(run_started_perf),
    )
    write_run_manifest(
        output_layout,
        state,
        success=success,
        error=error,
        error_type=error_type,
        run_metadata=run_metadata,
        analysis_runs=analysis_runs,
        stage_timings=stage_timings,
    )
    write_report(
        output_layout,
        state,
        success=success,
        error=error,
        error_type=error_type,
        run_metadata=run_metadata,
        analysis_runs=analysis_runs,
        stage_timings=stage_timings,
    )


def _run_timed_analysis(
    analysis_runs: list[dict[str, object]],
    *,
    label: str,
    runner,
) -> ReanalyzeResult:
    started_at = _timestamp_now()
    started_perf = time.perf_counter()
    try:
        result = runner()
    except Exception as exc:
        analysis_runs.append(
            {
                "label": label,
                "started_at": started_at,
                "completed_at": _timestamp_now(),
                "elapsed_seconds": _elapsed_seconds(started_perf),
                "success": False,
                "error": str(exc),
                "error_type": type(exc).__name__,
            }
        )
        raise

    analysis_runs.append(
        {
            "label": label,
            "started_at": started_at,
            "completed_at": _timestamp_now(),
            "elapsed_seconds": _elapsed_seconds(started_perf),
            "success": True,
            "warning_count": result.warning_count,
            "diagnostics_path": str(result.diagnostics_path),
            "inference_dir": str(result.inference_dir),
            "wpi_log_path": str(result.wpi_log_path),
            "adapter_metadata_path": str(result.adapter_metadata_path),
        }
    )
    return result


def _run_timed_stage(
    stage_timings: list[dict[str, object]],
    *,
    stage_name: str,
    runner,
) -> StageResult:
    started_at = _timestamp_now()
    started_perf = time.perf_counter()
    try:
        result = runner()
    except Exception as exc:
        stage_timings.append(
            {
                "stage": stage_name,
                "started_at": started_at,
                "completed_at": _timestamp_now(),
                "elapsed_seconds": _elapsed_seconds(started_perf),
                "success": False,
                "error": str(exc),
                "error_type": type(exc).__name__,
            }
        )
        raise

    stage_timings.append(
        {
            "stage": stage_name,
            "started_at": started_at,
            "completed_at": _timestamp_now(),
            "elapsed_seconds": _elapsed_seconds(started_perf),
            "success": result.success,
            "changed": result.changed,
            "rerun_required": result.rerun_required,
            "changed_files": list(result.changed_files),
            "artifacts": dict(result.artifacts),
        }
    )
    return result


def _build_run_metadata(
    *,
    state: PipelineState,
    output_layout: OutputLayout,
    run_started_at: str,
    run_completed_at: str,
    run_elapsed_seconds: float,
) -> dict[str, object]:
    return {
        "started_at": run_started_at,
        "completed_at": run_completed_at,
        "elapsed_seconds": run_elapsed_seconds,
        "command": state.config.command,
        "repo_root": str(state.config.repo_root),
        "workspace_root": str(state.workspace_root),
        "keep_workspace": state.config.keep_workspace,
        "build_args": list(state.config.build_args),
        "compile_target": state.config.compile_target,
        "artifacts_root": str(state.artifacts_root),
        "output_dir": str(output_layout.root),
    }


def _timestamp_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _elapsed_seconds(started_perf: float) -> float:
    return round(time.perf_counter() - started_perf, 6)


def _initial_state(config: RunConfig, *, workspace_root: Path, artifacts_root: Path) -> PipelineState:
    try:
        adapter = select_build_adapter(
            workspace_root,
            compile_target=config.compile_target,
            build_args=config.build_args,
            build_command=config.build_command,
        )
        selection = adapter.detect()
    except UnsupportedProjectError:
        selection = default_build_tool_selection()
    return PipelineState(
        config=config,
        workspace_root=workspace_root,
        build_system=selection.build_system,
        adapter_name=selection.adapter_name,
        current_analysis=None,
        stage_history=[],
        artifacts_root=artifacts_root,
        final_patch_manifest=None,
        legacy_regression_enabled=False,
    )


@dataclass
class _RepairExecutionState:
    current_analysis: ReanalyzeResult
    rlfixer_result: StageResult | None = None


