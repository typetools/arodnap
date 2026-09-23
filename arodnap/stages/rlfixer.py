from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path
import re

from arodnap.contracts import RunConfig, StageResult
from arodnap.runtime import java_executable

from .base import (
    BaseStageWrapper,
    StageExecutionError,
    append_command_log,
    run_stage_command,
    stage_timeout_seconds,
    write_stage_result,
)
from .rlfixer_io import (
    SOURCE_LEVEL_FIXES_MARKER,
    parse_checker_warnings,
    rlfixer_warnings_argument,
)

_STAGE_NAME = "rlfixer"
_FIX_ENTRY = re.compile(r"^\d+\] ", re.MULTILINE)


@dataclass(frozen=True)
class RLFixerStagePaths:
    root: Path
    log_path: Path
    fixes_path: Path
    debug_path: Path
    inputs_dir: Path
    sources_path: Path
    app_classes_path: Path
    warnings_path: Path

    @classmethod
    def for_stage(cls, stage_output_dir: Path) -> "RLFixerStagePaths":
        root = stage_output_dir.resolve()
        inputs_dir = root / "inputs"
        return cls(
            root=root,
            log_path=root / "stage.log",
            fixes_path=root / "fixes.txt",
            debug_path=root / "debug.txt",
            inputs_dir=inputs_dir,
            sources_path=inputs_dir / "sources.txt",
            app_classes_path=inputs_dir / "app_classes.txt",
            warnings_path=inputs_dir / "warnings.txt",
        )

    def ensure(self) -> None:
        self.inputs_dir.mkdir(parents=True, exist_ok=True)
        self.log_path.write_text("")


@dataclass(frozen=True)
class RLFixerStageInputs:
    config: RunConfig
    workspace_root: Path
    diagnostics_path: Path
    inference_dir: Path
    source_root: Path
    source_files_file: Path
    app_classes_file: Path
    classpath_entries_file: Path
    paths: RLFixerStagePaths


@dataclass(frozen=True)
class RLFixerStageOutputs:
    paths: RLFixerStagePaths
    warning_count: int
    fix_count: int
    notes: tuple[str, ...]


class RLFixerStageWrapper(BaseStageWrapper):
    """Runs RLFixer directly on build-discovered inputs.

    RLFixer's contract: `-projectDir` is the source root, `-srcFiles` lists sources relative
    to it, `-warnings` names files the same way, and `-classpath` is a path list that WALA
    loads as application code (compiled main outputs plus dependencies).
    """

    name = _STAGE_NAME

    def run(
        self,
        *,
        config: RunConfig,
        workspace_root: Path,
        diagnostics_path: Path,
        inference_dir: Path,
        source_root: Path,
        source_files_file: Path,
        app_classes_file: Path,
        classpath_entries_file: Path,
        stage_output_dir: Path,
    ) -> StageResult:
        inputs = RLFixerStageInputs(
            config=config,
            workspace_root=workspace_root.resolve(),
            diagnostics_path=diagnostics_path.resolve(),
            inference_dir=inference_dir.resolve(),
            source_root=source_root.resolve(),
            source_files_file=source_files_file.resolve(),
            app_classes_file=app_classes_file.resolve(),
            classpath_entries_file=classpath_entries_file.resolve(),
            paths=RLFixerStagePaths.for_stage(stage_output_dir),
        )
        outputs = super().run(inputs=inputs)
        result = StageResult(
            stage=self.name,
            changed=False,
            changed_files=[],
            rerun_required=False,
            artifacts={
                "log": str(outputs.paths.log_path),
                "fixes": str(outputs.paths.fixes_path),
                "debug": str(outputs.paths.debug_path),
                "inputs": str(outputs.paths.inputs_dir),
            },
            notes=list(outputs.notes),
            success=True,
        )
        write_stage_result(inputs.paths.root, result)
        return result

    def validate_inputs(self, **kwargs: object) -> None:
        inputs = _stage_inputs(kwargs)
        _require_directory(inputs.workspace_root, "workspace root")
        _require_directory(inputs.source_root, "source root")
        _require_directory(inputs.inference_dir, "inference directory")
        for path, label in (
            (inputs.diagnostics_path, "diagnostics file"),
            (inputs.source_files_file, "source files file"),
            (inputs.app_classes_file, "app classes file"),
            (inputs.classpath_entries_file, "classpath entries file"),
            (inputs.config.rlfixer_jar, "RLFixer jar"),
        ):
            _require_file(path, label)
        inputs.paths.ensure()

    def invoke_tool(self, **kwargs: object) -> RLFixerStageOutputs:
        inputs = _stage_inputs(kwargs)
        paths = inputs.paths

        warnings = parse_checker_warnings(inputs.diagnostics_path.read_text(errors="replace"))
        warnings_argument, skipped = rlfixer_warnings_argument(warnings, source_root=inputs.source_root)
        paths.warnings_path.write_text(warnings_argument.replace("#", "\n"))
        notes = [
            f"Skipped leak warning outside source root {inputs.source_root}: {warning.filepath}:{warning.line_number}"
            for warning in skipped
        ]

        if not warnings_argument:
            paths.fixes_path.write_text("")
            paths.debug_path.write_text("")
            notes.append("No resource leak warnings to repair.")
            return RLFixerStageOutputs(paths=paths, warning_count=0, fix_count=0, notes=tuple(notes))

        paths.sources_path.write_text(_relative_source_list(inputs.source_files_file, inputs.source_root))
        paths.app_classes_path.write_text(inputs.app_classes_file.read_text())
        classpath = os.pathsep.join(
            line.strip() for line in inputs.classpath_entries_file.read_text().splitlines() if line.strip()
        )

        command = [
            java_executable(),
            "-jar",
            str(inputs.config.rlfixer_jar.resolve()),
            "-classpath",
            classpath,
            "-warnings",
            warnings_argument,
            "-appClasses",
            str(paths.app_classes_path),
            "-projectDir",
            str(inputs.source_root),
            "-srcFiles",
            str(paths.sources_path),
            "-debugOutput",
            str(paths.debug_path),
            "-wpiOutDir",
            str(inputs.inference_dir),
        ]
        completed = run_stage_command(command=command, cwd=paths.root)
        append_command_log(
            paths.log_path,
            title="rlfixer",
            command=command,
            completed=completed,
            tool_name=self.name,
            timeout_seconds=stage_timeout_seconds(inputs.config),
        )
        if completed.returncode != 0:
            raise StageExecutionError(f"RLFixer failed. See log: {paths.log_path}")
        if SOURCE_LEVEL_FIXES_MARKER not in completed.stdout:
            raise StageExecutionError(f"RLFixer did not produce a fixes report. See log: {paths.log_path}")
        paths.fixes_path.write_text(completed.stdout)

        warning_count = warnings_argument.count("#")
        fix_count = len(_FIX_ENTRY.findall(completed.stdout))
        notes.append(f"RLFixer proposed {fix_count} fix(es) for {warning_count} leak warning(s).")
        return RLFixerStageOutputs(paths=paths, warning_count=warning_count, fix_count=fix_count, notes=tuple(notes))

    def validate_outputs(self, normalized_result: object, **kwargs: object) -> None:
        outputs = normalized_result
        if not isinstance(outputs, RLFixerStageOutputs):
            raise StageExecutionError("RLFixer stage received invalid outputs.")
        for path, label in ((outputs.paths.fixes_path, "fixes"), (outputs.paths.debug_path, "debug")):
            if not path.is_file():
                raise StageExecutionError(f"RLFixer stage did not produce its {label} artifact: {path}")


def run_rlfixer_stage(
    *,
    config: RunConfig,
    workspace_root: Path,
    diagnostics_path: Path,
    inference_dir: Path,
    source_root: Path,
    source_files_file: Path,
    app_classes_file: Path,
    classpath_entries_file: Path,
    stage_output_dir: Path,
) -> StageResult:
    return _WRAPPER.run(
        config=config,
        workspace_root=workspace_root,
        diagnostics_path=diagnostics_path,
        inference_dir=inference_dir,
        source_root=source_root,
        source_files_file=source_files_file,
        app_classes_file=app_classes_file,
        classpath_entries_file=classpath_entries_file,
        stage_output_dir=stage_output_dir,
    )


def _relative_source_list(source_files_file: Path, source_root: Path) -> str:
    relative = []
    for line in source_files_file.read_text().splitlines():
        if not line.strip():
            continue
        try:
            relative.append(Path(line.strip()).resolve().relative_to(source_root).as_posix())
        except ValueError as exc:
            raise StageExecutionError(f"Source file {line.strip()} is outside source root {source_root}.") from exc
    return "\n".join(relative) + "\n"


def _stage_inputs(kwargs: dict[str, object]) -> RLFixerStageInputs:
    inputs = kwargs["inputs"]
    if not isinstance(inputs, RLFixerStageInputs):
        raise StageExecutionError("RLFixer stage received invalid inputs.")
    return inputs


def _require_file(path: Path, label: str) -> None:
    if not path.resolve().is_file():
        raise StageExecutionError(f"Missing {label} for RLFixer stage: {path}")


def _require_directory(path: Path, label: str) -> None:
    if not path.resolve().is_dir():
        raise StageExecutionError(f"Missing {label} for RLFixer stage: {path}")


_WRAPPER = RLFixerStageWrapper()


__all__ = ["RLFixerStageWrapper", "StageExecutionError", "run_rlfixer_stage"]
