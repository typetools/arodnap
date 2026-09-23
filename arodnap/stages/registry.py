from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from arodnap.contracts import ReanalyzeResult, RunConfig, StageResult
from arodnap.orchestrator.results import OutputLayout

from .base import CompileInputs
from .bundle import run_bundle_stage
from .close_injector import run_close_injector_stage
from .owning_field import run_owning_field_stage
from .rlfixer import run_rlfixer_stage
from .rlpatcher import run_rlpatcher_stage


@dataclass(frozen=True)
class StageRunInput:
    config: RunConfig
    workspace_root: Path
    output_layout: OutputLayout
    current_analysis: ReanalyzeResult
    rlfixer_result: StageResult | None
    prior_results: tuple[StageResult, ...] = ()


StageRunner = Callable[[StageRunInput], StageResult]


@dataclass(frozen=True)
class RepairStageDefinition:
    name: str
    runner: StageRunner = field(compare=False, hash=False)
    rerun_analysis_label: str | None = None
    # When True, the pipeline captures this stage's result as the rlfixer_result
    # and forwards it to subsequent stages via StageRunInput.
    captures_rlfixer_result: bool = False
    # When True, the pipeline promotes this stage's "patch_manifest" artifact to
    # the top-level patches/manifest.json after the stage completes.
    promotes_patch_manifest: bool = False


def _compile_inputs(analysis: ReanalyzeResult) -> CompileInputs:
    return CompileInputs(
        sources_file=analysis.source_files_file,
        classpath_file=analysis.classpath_entries_file,
    )


def _run_close_injector(inp: StageRunInput) -> StageResult:
    return run_close_injector_stage(
        inp.config,
        workspace_root=inp.workspace_root,
        diagnostics_path=inp.current_analysis.diagnostics_path,
        stage_output_dir=inp.output_layout.stage_dir("close_injector"),
        compile_inputs=_compile_inputs(inp.current_analysis),
    )


def _run_owning_field(inp: StageRunInput) -> StageResult:
    return run_owning_field_stage(
        inp.config,
        workspace_root=inp.workspace_root,
        diagnostics_path=inp.current_analysis.diagnostics_path,
        stage_output_dir=inp.output_layout.stage_dir("owning_field"),
        compile_inputs=_compile_inputs(inp.current_analysis),
    )


def _run_rlfixer(inp: StageRunInput) -> StageResult:
    return run_rlfixer_stage(
        config=inp.config,
        workspace_root=inp.workspace_root,
        diagnostics_path=inp.current_analysis.diagnostics_path,
        inference_dir=inp.current_analysis.inference_dir,
        source_root=_load_source_root(inp.current_analysis.adapter_metadata_path),
        source_files_file=inp.current_analysis.source_files_file,
        app_classes_file=inp.current_analysis.app_classes_file,
        classpath_entries_file=inp.current_analysis.classpath_entries_file,
        stage_output_dir=inp.output_layout.stage_dir("rlfixer"),
    )


def _run_rlpatcher(inp: StageRunInput) -> StageResult:
    if inp.rlfixer_result is None:
        raise RuntimeError("RLFixer result is required before running RLPatcher.")
    return run_rlpatcher_stage(
        config=inp.config,
        workspace_root=inp.workspace_root,
        diagnostics_path=inp.current_analysis.diagnostics_path,
        inference_dir=inp.current_analysis.inference_dir,
        fixes_path=Path(inp.rlfixer_result.artifacts["fixes"]),
        debug_path=Path(inp.rlfixer_result.artifacts["debug"]),
        stage_output_dir=inp.output_layout.stage_dir("rlpatcher"),
        rlpatcher_jar=inp.config.rlpatcher_jar,
        source_root=_load_source_root(inp.current_analysis.adapter_metadata_path),
        compile_inputs=_compile_inputs(inp.current_analysis),
    )


def _run_bundle(inp: StageRunInput) -> StageResult:
    candidate_files = [
        changed_file for result in inp.prior_results for changed_file in result.changed_files
    ]
    for line in inp.current_analysis.source_files_file.read_text().splitlines():
        if line.strip():
            candidate_files.append(Path(line.strip()).resolve().relative_to(inp.workspace_root.resolve()).as_posix())
    return run_bundle_stage(
        repo_root=inp.config.repo_root,
        workspace_root=inp.workspace_root,
        candidate_files=candidate_files,
        stage_output_dir=inp.output_layout.stage_dir("bundle"),
    )


def _load_source_root(adapter_metadata_path: Path) -> Path:
    try:
        payload = json.loads(adapter_metadata_path.read_text())
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"Malformed adapter metadata JSON: {adapter_metadata_path}") from exc
    source_root = payload.get("source_root")
    if not isinstance(source_root, str) or not source_root:
        raise RuntimeError(f"Adapter metadata missing source_root: {adapter_metadata_path}")
    return Path(source_root).resolve()


REPAIR_STAGE_REGISTRY: tuple[RepairStageDefinition, ...] = (
    RepairStageDefinition(
        name="close_injector",
        runner=_run_close_injector,
        rerun_analysis_label="post_close_injector",
    ),
    RepairStageDefinition(
        name="owning_field",
        runner=_run_owning_field,
        rerun_analysis_label="post_owning_field",
    ),
    RepairStageDefinition(
        name="rlfixer",
        runner=_run_rlfixer,
        captures_rlfixer_result=True,
    ),
    RepairStageDefinition(
        name="rlpatcher",
        runner=_run_rlpatcher,
        rerun_analysis_label="final",
    ),
    RepairStageDefinition(
        name="bundle",
        runner=_run_bundle,
        promotes_patch_manifest=True,
    ),
)

REPAIR_STAGE_ORDER: tuple[str, ...] = tuple(stage.name for stage in REPAIR_STAGE_REGISTRY)


def get_repair_stage_definition(name: str) -> RepairStageDefinition:
    for stage in REPAIR_STAGE_REGISTRY:
        if stage.name == name:
            return stage
    raise KeyError(f"Unknown repair stage: {name}")


__all__ = [
    "REPAIR_STAGE_ORDER",
    "REPAIR_STAGE_REGISTRY",
    "RepairStageDefinition",
    "StageRunInput",
    "StageRunner",
    "get_repair_stage_definition",
]
