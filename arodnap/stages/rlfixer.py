from __future__ import annotations

from dataclasses import dataclass
import json
import shutil
from pathlib import Path
import sys

from arodnap.contracts import RunConfig, StageResult

from .base import (
    BaseStageWrapper,
    StageExecutionError,
    append_command_log,
    run_stage_command,
    stage_timeout_seconds,
    write_stage_result,
)

_STAGE_NAME = "rlfixer"


@dataclass(frozen=True)
class RLFixerStagePaths:
    root: Path
    staged_bundle_root: Path
    log_path: Path
    fixes_path: Path
    debug_path: Path
    results_file: Path
    runner_output_dir: Path
    runner_debug_dir: Path

    @classmethod
    def for_stage(cls, stage_output_dir: Path) -> "RLFixerStagePaths":
        root = stage_output_dir.resolve()
        return cls(
            root=root,
            staged_bundle_root=root / "compat_bundle",
            log_path=root / "stage.log",
            fixes_path=root / "fixes.txt",
            debug_path=root / "debug.txt",
            results_file=root / "compat_bundle.txt",
            runner_output_dir=root / "_runner_output",
            runner_debug_dir=root / "_runner_debug",
        )

    def ensure(self) -> None:
        self.root.mkdir(parents=True, exist_ok=True)


@dataclass(frozen=True)
class RLFixerStageInputs:
    config: RunConfig | None
    workspace_root: Path
    diagnostics_path: Path
    inference_dir: Path
    compatibility_bundle_root: Path
    paths: RLFixerStagePaths


@dataclass(frozen=True)
class RLFixerStageInvocation:
    inputs: RLFixerStageInputs
    staged_bundle_root: Path
    command: tuple[str, ...]
    completed_returncode: int
    stdout: str
    stderr: str


@dataclass(frozen=True)
class RLFixerStageOutputs:
    paths: RLFixerStagePaths
    staged_bundle_root: Path
    fixes_path: Path
    debug_path: Path


class RLFixerStageWrapper(BaseStageWrapper):
    name = _STAGE_NAME

    def run(
        self,
        *,
        config: RunConfig | None = None,
        workspace_root: Path,
        diagnostics_path: Path,
        inference_dir: Path,
        compatibility_bundle_root: Path,
        stage_output_dir: Path,
    ) -> StageResult:
        normalized_inputs = RLFixerStageInputs(
            config=config,
            workspace_root=workspace_root.resolve(),
            diagnostics_path=diagnostics_path.resolve(),
            inference_dir=inference_dir.resolve(),
            compatibility_bundle_root=compatibility_bundle_root.resolve(),
            paths=RLFixerStagePaths.for_stage(stage_output_dir),
        )
        outputs = super().run(inputs=normalized_inputs)
        result = self._create_stage_result(outputs)
        write_stage_result(normalized_inputs.paths.root, result)
        return result

    def validate_inputs(self, **kwargs: object) -> None:
        inputs = self._stage_inputs(kwargs)
        if not inputs.workspace_root.is_dir():
            raise StageExecutionError(
                f"Missing workspace root for RLFixer stage: {inputs.workspace_root}"
            )
        if not inputs.diagnostics_path.is_file():
            raise StageExecutionError(
                f"Missing diagnostics file for RLFixer stage: {inputs.diagnostics_path}"
            )
        if not inputs.inference_dir.is_dir():
            raise StageExecutionError(
                f"Missing inference directory for RLFixer stage: {inputs.inference_dir}"
            )
        _validate_compatibility_bundle(inputs.compatibility_bundle_root)
        inputs.paths.ensure()

    def invoke_tool(self, **kwargs: object) -> RLFixerStageInvocation:
        inputs = self._stage_inputs(kwargs)
        staged_bundle_root = _stage_compatibility_bundle(
            compatibility_bundle_root=inputs.compatibility_bundle_root,
            stage_output_dir=inputs.paths.root,
        )
        _reset_directory(inputs.paths.runner_output_dir)
        _reset_directory(inputs.paths.runner_debug_dir)
        shutil.copyfile(inputs.diagnostics_path, inputs.paths.results_file)

        command = self._build_command(inputs)
        completed = run_stage_command(command=command, cwd=_repo_root())
        append_command_log(
            inputs.paths.log_path,
            title="rlfixer_runner",
            command=command,
            completed=completed,
            tool_name=self.name,
            timeout_seconds=stage_timeout_seconds(inputs.config),
        )

        if completed.returncode != 0:
            raise StageExecutionError(f"RLFixer stage failed. See log: {inputs.paths.log_path}")

        return RLFixerStageInvocation(
            inputs=inputs,
            staged_bundle_root=staged_bundle_root,
            command=tuple(command),
            completed_returncode=completed.returncode,
            stdout=completed.stdout,
            stderr=completed.stderr,
        )

    def normalize_outputs(self, tool_result: object, **kwargs: object) -> RLFixerStageOutputs:
        invocation = self._stage_invocation(tool_result)
        runner_fixes_path = invocation.inputs.paths.runner_output_dir / "compat_bundle.txt"
        runner_debug_path = invocation.inputs.paths.runner_debug_dir / "compat_bundle.txt"

        # Harvest runner-emitted files into stable stage-local artifacts before returning.
        if runner_fixes_path.exists():
            shutil.move(str(runner_fixes_path), invocation.inputs.paths.fixes_path)
        else:
            invocation.inputs.paths.fixes_path.write_text("")
        if runner_debug_path.exists():
            shutil.move(str(runner_debug_path), invocation.inputs.paths.debug_path)
        else:
            invocation.inputs.paths.debug_path.write_text("")

        return RLFixerStageOutputs(
            paths=invocation.inputs.paths,
            staged_bundle_root=invocation.staged_bundle_root,
            fixes_path=invocation.inputs.paths.fixes_path,
            debug_path=invocation.inputs.paths.debug_path,
        )

    def validate_outputs(self, normalized_result: object, **kwargs: object) -> None:
        outputs = self._stage_outputs(normalized_result)
        metadata_path = outputs.staged_bundle_root / "metadata.json"

        if not outputs.fixes_path.is_file():
            raise StageExecutionError(f"RLFixer stage did not produce fixes artifact: {outputs.fixes_path}")
        if not outputs.debug_path.is_file():
            raise StageExecutionError(f"RLFixer stage did not produce debug artifact: {outputs.debug_path}")
        if not metadata_path.is_file():
            raise StageExecutionError(
                f"RLFixer stage did not preserve compatibility bundle metadata: {metadata_path}"
            )

    def _build_command(self, inputs: RLFixerStageInputs) -> list[str]:
        return [
            sys.executable,
            str(_repo_root() / "RLFixerRunner.py"),
            "--tool",
            "checkerframework",
            "--results",
            str(inputs.paths.results_file),
            "--benchmarks",
            str(inputs.paths.root),
            "--output",
            str(inputs.paths.runner_output_dir),
            "--debug_output",
            str(inputs.paths.runner_debug_dir),
            "--wpioutdir",
            str(inputs.inference_dir),
        ]

    def _create_stage_result(self, outputs: RLFixerStageOutputs) -> StageResult:
        return StageResult(
            stage=self.name,
            changed=False,
            changed_files=[],
            rerun_required=False,
            artifacts={
                "log": str(outputs.paths.log_path),
                "fixes": str(outputs.fixes_path),
                "debug": str(outputs.debug_path),
                "compatibility_bundle_metadata": str(outputs.staged_bundle_root / "metadata.json"),
            },
            notes=["RLFixer completed without mutating workspace sources."],
            success=True,
        )

    def _stage_inputs(self, kwargs: dict[str, object]) -> RLFixerStageInputs:
        inputs = kwargs["inputs"]
        if not isinstance(inputs, RLFixerStageInputs):
            raise StageExecutionError("RLFixer stage received invalid normalized inputs.")
        return inputs

    def _stage_invocation(self, tool_result: object) -> RLFixerStageInvocation:
        if not isinstance(tool_result, RLFixerStageInvocation):
            raise StageExecutionError("RLFixer stage received invalid tool invocation outputs.")
        return tool_result

    def _stage_outputs(self, normalized_result: object) -> RLFixerStageOutputs:
        if not isinstance(normalized_result, RLFixerStageOutputs):
            raise StageExecutionError("RLFixer stage received invalid normalized outputs.")
        return normalized_result


def run_rlfixer_stage(
    *,
    config: RunConfig | None = None,
    workspace_root: Path,
    diagnostics_path: Path,
    inference_dir: Path,
    compatibility_bundle_root: Path,
    stage_output_dir: Path,
) -> StageResult:
    return _WRAPPER.run(
        config=config,
        workspace_root=workspace_root,
        diagnostics_path=diagnostics_path,
        inference_dir=inference_dir,
        compatibility_bundle_root=compatibility_bundle_root,
        stage_output_dir=stage_output_dir,
    )


def _validate_compatibility_bundle(bundle_root: Path) -> Path:
    bundle_root = bundle_root.resolve()
    if not bundle_root.is_dir():
        raise StageExecutionError(f"Missing RLFixer compatibility bundle root: {bundle_root}")

    info_dir = bundle_root / "info"
    classes_file = info_dir / "classes"
    sources_file = info_dir / "sources"
    metadata_path = bundle_root / "metadata.json"
    jar_dir = bundle_root / "jarfile"

    for required_file in (classes_file, sources_file, metadata_path):
        if not required_file.is_file():
            raise StageExecutionError(f"Missing RLFixer compatibility bundle file: {required_file}")

    if not jar_dir.is_dir():
        raise StageExecutionError(f"Missing RLFixer compatibility jar directory: {jar_dir}")

    jar_files = sorted(path for path in jar_dir.iterdir() if path.is_file() and path.suffix == ".jar")
    if len(jar_files) != 1:
        raise StageExecutionError(
            f"RLFixer compatibility bundle must contain exactly one jar file in {jar_dir}"
        )

    try:
        metadata = json.loads(metadata_path.read_text())
    except json.JSONDecodeError as exc:
        raise StageExecutionError(
            f"Malformed RLFixer compatibility bundle metadata: {metadata_path}"
        ) from exc
    if not isinstance(metadata, dict):
        raise StageExecutionError(
            f"Malformed RLFixer compatibility bundle metadata: {metadata_path}"
        )

    return bundle_root


def _stage_compatibility_bundle(*, compatibility_bundle_root: Path, stage_output_dir: Path) -> Path:
    staged_root = (stage_output_dir / "compat_bundle").resolve()
    if staged_root == compatibility_bundle_root:
        return staged_root

    if staged_root.exists():
        shutil.rmtree(staged_root)
    shutil.copytree(compatibility_bundle_root, staged_root)
    return staged_root


def _reset_directory(path: Path) -> None:
    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True, exist_ok=True)


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


_WRAPPER = RLFixerStageWrapper()


__all__ = ["RLFixerStageWrapper", "StageExecutionError", "run_rlfixer_stage"]
