from __future__ import annotations

from abc import ABC, abstractmethod
import json
from dataclasses import dataclass
from pathlib import Path
import subprocess
from typing import Protocol

from arodnap.contracts import RunConfig, StageResult
from arodnap.patch_tool import PatchExecution, PatchToolError, append_patch_execution_log, run_patch
from arodnap.runtime import CommandExecutionError, CommandResult, CommandTimeoutError, render_command_log, run_command


class StageExecutionError(RuntimeError):
    """Raised when a stage tool fails or its outputs cannot be normalized."""


class StageTimeoutError(StageExecutionError):
    """Raised when a stage tool runs longer than the user's --stage-timeout."""


class StageNotImplementedError(NotImplementedError):
    """Raised by placeholder stage modules before wrapper implementation."""


class StageWrapperContract(Protocol):
    name: str

    def validate_inputs(self, **kwargs: object) -> None:
        ...

    def invoke_tool(self, **kwargs: object) -> object:
        ...

    def normalize_outputs(self, tool_result: object, **kwargs: object) -> object:
        ...

    def validate_outputs(self, normalized_result: object, **kwargs: object) -> None:
        ...

    def run(self, **kwargs: object) -> object:
        ...


class BaseStageWrapper(ABC):
    """Lightweight common execution shape for future stage-wrapper migrations."""

    name: str

    def validate_inputs(self, **kwargs: object) -> None:
        return None

    @abstractmethod
    def invoke_tool(self, **kwargs: object) -> object:
        raise StageNotImplementedError(f"Stage '{self.name}' does not implement invoke_tool().")

    def normalize_outputs(self, tool_result: object, **kwargs: object) -> object:
        return tool_result

    def validate_outputs(self, normalized_result: object, **kwargs: object) -> None:
        return None

    def run(self, **kwargs: object) -> object:
        self.validate_inputs(**kwargs)
        tool_result = self.invoke_tool(**kwargs)
        normalized_result = self.normalize_outputs(tool_result, **kwargs)
        self.validate_outputs(normalized_result, **kwargs)
        return normalized_result


@dataclass(frozen=True)
class PatchStageToolInvocation:
    stage_paths: StagePaths
    raw_patch_path: Path
    command: list[str]
    completed: CommandResult


@dataclass(frozen=True)
class NormalizedPatchStageOutputs:
    stage_paths: StagePaths
    changed: bool
    changed_files: tuple[str, ...]
    patch_path: Path | None


@dataclass(frozen=True)
class CompileInputs:
    """Build-discovered facts the Java stage tools use to check that patches compile."""

    sources_file: Path
    classpath_file: Path

    def java_properties(self) -> list[str]:
        return [
            f"-Darodnap.sourcesFile={self.sources_file.resolve()}",
            f"-Darodnap.classpathFile={self.classpath_file.resolve()}",
        ]


@dataclass(frozen=True)
class StagePaths:
    root: Path
    log_path: Path
    patch_path: Path
    stage_result_path: Path

    @classmethod
    def for_stage(cls, stage_output_dir: Path, *, patch_filename: str) -> "StagePaths":
        root = stage_output_dir.resolve()
        return cls(
            root=root,
            log_path=root / "stage.log",
            patch_path=root / patch_filename,
            stage_result_path=root / "stage_result.json",
        )

    def ensure(self) -> None:
        self.root.mkdir(parents=True, exist_ok=True)


def stage_timeout_seconds(config: object | None) -> int | None:
    timeouts = getattr(config, "timeouts", None)
    return getattr(timeouts, "stage_seconds", None)


def run_stage_command(
    *,
    command: list[str],
    cwd: Path,
    timeout_seconds: int | None = None,
) -> CommandResult:
    try:
        return run_command(command, cwd=cwd, timeout_seconds=timeout_seconds)
    except CommandTimeoutError as exc:
        raise StageTimeoutError(f"{exc} (--stage-timeout)") from exc
    except CommandExecutionError as exc:
        raise StageExecutionError(str(exc)) from exc


def append_command_log(
    log_path: Path,
    *,
    title: str,
    command: list[str],
    completed: CommandResult | subprocess.CompletedProcess[str],
    tool_name: str | None = None,
    timeout_seconds: int | None = None,
) -> None:
    normalized = _as_command_result(command=command, completed=completed)
    lines = [
        f"== {title} ==",
        render_command_log(
            normalized,
            tool_name=tool_name,
            timeout_seconds=timeout_seconds,
        ),
        "",
    ]
    with log_path.open("a", encoding="utf-8") as handle:
        handle.write("\n".join(lines))


def _as_command_result(
    *,
    command: list[str],
    completed: CommandResult | subprocess.CompletedProcess[str],
) -> CommandResult:
    if isinstance(completed, CommandResult):
        return completed
    return CommandResult(
        command=tuple(str(part) for part in command),
        cwd=None,
        returncode=completed.returncode,
        stdout=completed.stdout,
        stderr=completed.stderr,
    )


class BaseNormalizedPatchStageWrapper(BaseStageWrapper, ABC):
    """Shared wrapper for Java tools that emit a raw unified diff to a requested path."""

    patch_filename: str
    raw_patch_filename: str
    command_log_title: str

    def run(
        self,
        *,
        config: RunConfig,
        workspace_root: Path,
        diagnostics_path: Path,
        stage_output_dir: Path,
        compile_inputs: CompileInputs | None = None,
    ) -> StageResult:
        workspace_root = workspace_root.resolve()
        diagnostics_path = diagnostics_path.resolve()
        stage_paths = StagePaths.for_stage(stage_output_dir, patch_filename=self.patch_filename)
        # The tool writes its raw diff outside the workspace so it can never leak into sources.
        raw_patch_path = stage_paths.root / self.raw_patch_filename

        self.validate_inputs(
            config=config,
            workspace_root=workspace_root,
            diagnostics_path=diagnostics_path,
            stage_paths=stage_paths,
            raw_patch_path=raw_patch_path,
        )
        invocation = self.invoke_tool(
            config=config,
            workspace_root=workspace_root,
            diagnostics_path=diagnostics_path,
            stage_paths=stage_paths,
            raw_patch_path=raw_patch_path,
            compile_inputs=compile_inputs,
        )
        normalized_outputs = self.normalize_outputs(
            invocation,
            workspace_root=workspace_root,
        )
        self.validate_outputs(normalized_outputs, workspace_root=workspace_root)
        result = self._finalize_result(
            normalized_outputs,
            workspace_root=workspace_root,
        )
        write_stage_result(stage_paths.root, result)
        return result

    def validate_inputs(self, **kwargs: object) -> None:
        diagnostics_path = Path(kwargs["diagnostics_path"]).resolve()
        stage_paths: StagePaths = kwargs["stage_paths"]  # type: ignore[assignment]
        if not diagnostics_path.is_file():
            raise StageExecutionError(
                f"Missing diagnostics file for {self.diagnostics_label()}: {diagnostics_path}"
            )
        stage_paths.ensure()

    def invoke_tool(self, **kwargs: object) -> PatchStageToolInvocation:
        config = kwargs["config"]
        workspace_root = Path(kwargs["workspace_root"]).resolve()
        diagnostics_path = Path(kwargs["diagnostics_path"]).resolve()
        stage_paths: StagePaths = kwargs["stage_paths"]  # type: ignore[assignment]
        raw_patch_path = Path(kwargs["raw_patch_path"]).resolve()
        compile_inputs = kwargs.get("compile_inputs")

        if raw_patch_path.exists():
            raw_patch_path.unlink()

        java_properties = [f"-Darodnap.patchFile={raw_patch_path}"]
        if compile_inputs is not None:
            java_properties.extend(compile_inputs.java_properties())
        command = self.build_command(
            config=config,
            workspace_root=workspace_root,
            diagnostics_path=diagnostics_path,
            java_properties=java_properties,
        )
        completed = run_stage_command(
            command=command, cwd=workspace_root, timeout_seconds=stage_timeout_seconds(config)
        )
        append_command_log(
            stage_paths.log_path,
            title=self.command_log_title,
            command=command,
            completed=completed,
            tool_name=self.name,
            timeout_seconds=stage_timeout_seconds(config),
        )

        if completed.returncode != 0:
            raise StageExecutionError(
                self.tool_failure_message(
                    workspace_root=workspace_root,
                    log_path=stage_paths.log_path,
                )
            )

        return PatchStageToolInvocation(
            stage_paths=stage_paths,
            raw_patch_path=raw_patch_path,
            command=command,
            completed=completed,
        )

    def normalize_outputs(self, tool_result: object, **kwargs: object) -> NormalizedPatchStageOutputs:
        invocation = tool_result
        workspace_root = Path(kwargs["workspace_root"]).resolve()

        if not invocation.raw_patch_path.exists() or invocation.raw_patch_path.stat().st_size == 0:
            return NormalizedPatchStageOutputs(
                stage_paths=invocation.stage_paths,
                changed=False,
                changed_files=(),
                patch_path=None,
            )

        # Rewrite tool-emitted headers to repo-root-relative paths before exposing the patch artifact.
        normalized_patch, changed_files = normalize_unified_diff_paths(
            invocation.raw_patch_path.read_text(),
            workspace_root=workspace_root,
        )
        invocation.stage_paths.patch_path.write_text(normalized_patch)
        invocation.raw_patch_path.unlink(missing_ok=True)

        return NormalizedPatchStageOutputs(
            stage_paths=invocation.stage_paths,
            changed=True,
            changed_files=tuple(changed_files),
            patch_path=invocation.stage_paths.patch_path,
        )

    def validate_outputs(self, normalized_result: object, **kwargs: object) -> None:
        outputs = normalized_result
        if outputs.changed and outputs.patch_path is None:
            raise StageExecutionError(f"{self.name} wrapper reported changes without a normalized patch artifact.")
        if outputs.changed and not outputs.changed_files:
            raise StageExecutionError(f"{self.name} wrapper reported changes without changed_files.")
        if not outputs.changed and outputs.patch_path is not None:
            raise StageExecutionError(f"{self.name} wrapper reported a patch artifact for a noop result.")

    def _finalize_result(
        self,
        outputs: NormalizedPatchStageOutputs,
        *,
        workspace_root: Path,
    ) -> StageResult:
        if not outputs.changed:
            return StageResult(
                stage=self.name,
                changed=False,
                changed_files=[],
                rerun_required=False,
                artifacts={"log": str(outputs.stage_paths.log_path)},
                notes=[self.no_patch_note()],
                success=True,
            )

        apply_completed = self.apply_patch(
            workspace_root=workspace_root,
            patch_path=outputs.patch_path,
        )
        append_patch_execution_log(
            outputs.stage_paths.log_path,
            title="apply_normalized_patch",
            execution=apply_completed,
        )

        if apply_completed.completed.returncode != 0:
            raise StageExecutionError(
                self.patch_apply_failure_message(log_path=outputs.stage_paths.log_path)
            )

        return StageResult(
            stage=self.name,
            changed=True,
            changed_files=list(outputs.changed_files),
            rerun_required=True,
            artifacts={
                "log": str(outputs.stage_paths.log_path),
                "patch": str(outputs.patch_path),
            },
            notes=[self.changed_note(changed_files=list(outputs.changed_files))],
            success=True,
        )

    def apply_patch(
        self,
        *,
        workspace_root: Path,
        patch_path: Path | None,
    ) -> PatchExecution:
        if patch_path is None:
            raise StageExecutionError(f"{self.name} wrapper cannot apply a missing normalized patch.")
        return apply_normalized_patch(
            workspace_root=workspace_root,
            patch_path=patch_path,
            extra_args=self.patch_apply_extra_args(),
        )

    def patch_apply_extra_args(self) -> list[str] | None:
        return None

    @abstractmethod
    def build_command(
        self,
        *,
        config: RunConfig,
        workspace_root: Path,
        diagnostics_path: Path,
        java_properties: list[str],
    ) -> list[str]:
        raise StageNotImplementedError(f"Stage '{self.name}' does not implement build_command().")

    @abstractmethod
    def diagnostics_label(self) -> str:
        raise StageNotImplementedError(f"Stage '{self.name}' does not implement diagnostics_label().")

    @abstractmethod
    def tool_failure_message(self, *, workspace_root: Path, log_path: Path) -> str:
        raise StageNotImplementedError(f"Stage '{self.name}' does not implement tool_failure_message().")

    @abstractmethod
    def patch_apply_failure_message(self, *, log_path: Path) -> str:
        raise StageNotImplementedError(
            f"Stage '{self.name}' does not implement patch_apply_failure_message()."
        )

    @abstractmethod
    def no_patch_note(self) -> str:
        raise StageNotImplementedError(f"Stage '{self.name}' does not implement no_patch_note().")

    @abstractmethod
    def changed_note(self, *, changed_files: list[str]) -> str:
        raise StageNotImplementedError(f"Stage '{self.name}' does not implement changed_note().")


def apply_normalized_patch(
    *,
    workspace_root: Path,
    patch_path: Path,
    extra_args: list[str] | None = None,
) -> PatchExecution:
    try:
        return run_patch(
            cwd=workspace_root,
            patch_path=patch_path,
            strip_level=0,
            check_only=False,
            require_gnu=True,
            operation_label="stage patch apply",
            extra_args=extra_args,
            forward=True,
            ignore_whitespace=True,
        )
    except PatchToolError as exc:
        raise StageExecutionError(str(exc)) from exc


def write_stage_result(stage_output_dir: Path, result: StageResult) -> Path:
    stage_result_path = stage_output_dir.resolve() / "stage_result.json"
    stage_result_path.parent.mkdir(parents=True, exist_ok=True)
    stage_result_path.write_text(json.dumps(result.to_dict(), indent=2, sort_keys=True) + "\n")
    return stage_result_path


def normalize_unified_diff_paths(patch_text: str, *, workspace_root: Path) -> tuple[str, list[str]]:
    """Rewrite a tool's unified diff so both headers name the repo-relative target file.

    Tools label one side of each header pair with a temporary file (e.g. `--- <workspace file>`
    / `+++ /tmp/patch-123.java`), so each pair resolves to whichever path is in the workspace.
    """
    workspace_root = workspace_root.resolve()
    changed_files: list[str] = []
    seen_files: set[str] = set()
    normalized_lines: list[str] = []
    lines = patch_text.replace("\r\n", "\n").splitlines()
    saw_header = False
    index = 0

    while index < len(lines):
        line = lines[index]
        if line.startswith("--- "):
            if index + 1 >= len(lines) or not lines[index + 1].startswith("+++ "):
                raise StageExecutionError("Patch output did not contain a valid unified diff header pair.")
            old_path, old_separator, old_suffix = _parse_patch_header(lines[index], prefix="--- ")
            new_path, new_separator, new_suffix = _parse_patch_header(lines[index + 1], prefix="+++ ")
            target_path = _resolve_target_path(
                old_path=old_path,
                new_path=new_path,
                workspace_root=workspace_root,
            )
            normalized_old = "/dev/null" if old_path == "/dev/null" else target_path
            normalized_new = "/dev/null" if new_path == "/dev/null" else target_path
            normalized_lines.append(f"--- {normalized_old}{old_separator}{old_suffix}")
            normalized_lines.append(f"+++ {normalized_new}{new_separator}{new_suffix}")
            if target_path not in seen_files:
                changed_files.append(target_path)
                seen_files.add(target_path)
            saw_header = True
            index += 2
            continue

        if line.startswith("+++ "):
            raise StageExecutionError("Patch output contained an unexpected unified diff header order.")

        normalized_lines.append(line)
        index += 1

    if not saw_header:
        raise StageExecutionError("Patch output did not contain unified diff file headers.")
    if not changed_files:
        raise StageExecutionError("Patch output did not reference any repo files.")

    return "\n".join(normalized_lines) + "\n", changed_files


def _parse_patch_header(line: str, *, prefix: str) -> tuple[str, str, str]:
    payload = line[len(prefix) :]
    path_text, separator, suffix = payload.partition("\t")
    return path_text.strip(), separator, suffix


def _resolve_target_path(*, old_path: str, new_path: str, workspace_root: Path) -> str:
    for candidate in (new_path, old_path):
        normalized = _normalize_candidate_path(candidate, workspace_root=workspace_root)
        if normalized is not None:
            return normalized
    raise StageExecutionError(
        f"Patch paths did not reference a file under workspace root {workspace_root}: {old_path} -> {new_path}"
    )


def _normalize_candidate_path(path_text: str, *, workspace_root: Path) -> str | None:
    if path_text == "/dev/null":
        return None

    candidate = Path(path_text)
    if candidate.is_absolute():
        try:
            return candidate.resolve().relative_to(workspace_root).as_posix()
        except ValueError:
            return None

    relative = Path(path_text.removeprefix("./"))
    if relative.is_absolute() or ".." in relative.parts:
        raise StageExecutionError(f"Unsupported patch path outside workspace root: {path_text}")
    return relative.as_posix()


__all__ = [
    "BaseStageWrapper",
    "BaseNormalizedPatchStageWrapper",
    "CompileInputs",
    "NormalizedPatchStageOutputs",
    "PatchStageToolInvocation",
    "StageExecutionError",
    "StageNotImplementedError",
    "StagePaths",
    "StageWrapperContract",
    "apply_normalized_patch",
    "append_command_log",
    "normalize_unified_diff_paths",
    "run_stage_command",
    "write_stage_result",
]
