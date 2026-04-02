from __future__ import annotations

import json
from pathlib import Path

from arodnap.apply_support import apply_patch_bundle
from arodnap.analysis.analyze import analyze_once
from arodnap.analysis.reanalyze import reanalyze
from arodnap.compat.rlfixer_inputs import generate_rlfixer_compatibility_bundle
from arodnap.contracts import PipelineState, RunConfig
from arodnap.orchestrator.results import OutputLayout, write_report, write_run_manifest
from arodnap.orchestrator.workspace import copied_workspace
from arodnap.stages.close_injector import run_close_injector_stage
from arodnap.stages.owning_field import run_owning_field_stage
from arodnap.stages.rlfixer import run_rlfixer_stage
from arodnap.stages.rlpatcher import run_rlpatcher_stage


def run_analyze(config: RunConfig) -> int:
    return _run_analysis_command(config, analysis_runner=analyze_once)


def run_infer(config: RunConfig) -> int:
    return _run_analysis_command(config, analysis_runner=reanalyze)


def run_repair(config: RunConfig) -> int:
    output_layout = OutputLayout.from_root(config.out_dir)
    output_layout.ensure()

    with copied_workspace(config.repo_root, keep_workspace=config.keep_workspace) as workspace:
        state = _initial_state(config, workspace_root=workspace.workspace_root, artifacts_root=output_layout.root)

        try:
            current_analysis = reanalyze(
                config,
                workspace_root=workspace.workspace_root,
                label="initial",
                artifacts_root=output_layout.root,
            )
            state.current_analysis = current_analysis

            close_result = run_close_injector_stage(
                config,
                workspace_root=workspace.workspace_root,
                diagnostics_path=current_analysis.diagnostics_path,
                stage_output_dir=output_layout.stage_dir("close_injector"),
            )
            state.stage_history.append(close_result)
            if close_result.rerun_required:
                current_analysis = reanalyze(
                    config,
                    workspace_root=workspace.workspace_root,
                    label="post_close_injector",
                    artifacts_root=output_layout.root,
                )
                state.current_analysis = current_analysis

            owning_result = run_owning_field_stage(
                config,
                workspace_root=workspace.workspace_root,
                diagnostics_path=current_analysis.diagnostics_path,
                stage_output_dir=output_layout.stage_dir("owning_field"),
            )
            state.stage_history.append(owning_result)
            if owning_result.rerun_required:
                current_analysis = reanalyze(
                    config,
                    workspace_root=workspace.workspace_root,
                    label="post_owning_field",
                    artifacts_root=output_layout.root,
                )
                state.current_analysis = current_analysis

            compiled_outputs_root = _load_compiled_outputs_root(current_analysis.adapter_metadata_path)
            compatibility_bundle = generate_rlfixer_compatibility_bundle(
                workspace_root=workspace.workspace_root,
                source_files_file=current_analysis.source_files_file,
                app_classes_file=current_analysis.app_classes_file,
                classpath_entries_file=current_analysis.classpath_entries_file,
                compiled_outputs_root=compiled_outputs_root,
                stage_output_dir=output_layout.stage_dir("rlfixer"),
            )
            rlfixer_result = run_rlfixer_stage(
                workspace_root=workspace.workspace_root,
                diagnostics_path=current_analysis.diagnostics_path,
                inference_dir=current_analysis.inference_dir,
                compatibility_bundle_root=compatibility_bundle.root,
                stage_output_dir=output_layout.stage_dir("rlfixer"),
            )
            state.stage_history.append(rlfixer_result)

            rlpatcher_result = run_rlpatcher_stage(
                workspace_root=workspace.workspace_root,
                diagnostics_path=current_analysis.diagnostics_path,
                inference_dir=current_analysis.inference_dir,
                fixes_path=Path(rlfixer_result.artifacts["fixes"]),
                debug_path=Path(rlfixer_result.artifacts["debug"]),
                stage_output_dir=output_layout.stage_dir("rlpatcher"),
                rlpatcher_jar=config.rlpatcher_jar,
            )
            state.stage_history.append(rlpatcher_result)

            state.final_patch_manifest = output_layout.promote_patch_manifest(
                Path(rlpatcher_result.artifacts["patch_manifest"])
            )
            write_run_manifest(output_layout, state, success=True)
            write_report(output_layout, state, success=True)
        except Exception as exc:
            write_run_manifest(output_layout, state, success=False, error=str(exc))
            write_report(output_layout, state, success=False, error=str(exc))
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

    with copied_workspace(config.repo_root, keep_workspace=config.keep_workspace) as workspace:
        state = _initial_state(config, workspace_root=workspace.workspace_root, artifacts_root=output_layout.root)

        try:
            state.current_analysis = analysis_runner(
                config,
                workspace_root=workspace.workspace_root,
                label="initial",
                artifacts_root=output_layout.root,
            )
            write_run_manifest(output_layout, state, success=True)
            write_report(output_layout, state, success=True)
        except Exception as exc:
            write_run_manifest(output_layout, state, success=False, error=str(exc))
            write_report(output_layout, state, success=False, error=str(exc))
            raise

    return 0


def _initial_state(config: RunConfig, *, workspace_root: Path, artifacts_root: Path) -> PipelineState:
    return PipelineState(
        config=config,
        workspace_root=workspace_root,
        build_system="gradle",
        adapter_name="gradle-v1",
        current_analysis=None,
        stage_history=[],
        artifacts_root=artifacts_root,
        final_patch_manifest=None,
        legacy_regression_enabled=False,
    )


def _load_compiled_outputs_root(adapter_metadata_path: Path) -> Path:
    try:
        payload = json.loads(adapter_metadata_path.read_text())
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"Malformed adapter metadata JSON: {adapter_metadata_path}") from exc

    compiled_outputs_root = payload.get("compiled_classes_root")
    if not isinstance(compiled_outputs_root, str) or not compiled_outputs_root:
        raise RuntimeError(f"Adapter metadata missing compiled_classes_root: {adapter_metadata_path}")
    return Path(compiled_outputs_root).resolve()
