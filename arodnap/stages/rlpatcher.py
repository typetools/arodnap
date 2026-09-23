from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
from pathlib import Path

from arodnap.contracts import RunConfig, StageResult
from arodnap.patch_tool import PatchToolError, append_patch_execution_log, run_patch
from arodnap.runtime import java_executable

from .base import (
    BaseStageWrapper,
    CompileInputs,
    StageExecutionError,
    append_command_log,
    apply_normalized_patch,
    run_stage_command,
    stage_timeout_seconds,
    write_stage_result,
)
from .rlfixer_io import (
    CheckerWarning,
    FixSuggestion,
    build_rlpatcher_prompt,
    match_fixes_to_warnings,
    parse_checker_warnings,
    parse_debug_fixable,
    parse_fix_suggestions,
    select_fixable_suggestions,
)

_STAGE_NAME = "rlpatcher"
_PATCH_SUCCESS_TEXT = "Patch applied successfully"


@dataclass(frozen=True)
class RLPatcherStagePaths:
    root: Path
    log_path: Path
    patch_dir: Path
    prompt_dir: Path
    manifest_path: Path
    raw_patch_path: Path

    @classmethod
    def for_stage(cls, stage_output_dir: Path) -> "RLPatcherStagePaths":
        root = stage_output_dir.resolve()
        return cls(
            root=root,
            log_path=root / "stage.log",
            patch_dir=root / "patches",
            prompt_dir=root / "_tmp_prompts",
            manifest_path=root / "patch_manifest.json",
            raw_patch_path=root / "rlfixer.patch",
        )

    def ensure(self) -> None:
        self.root.mkdir(parents=True, exist_ok=True)
        self.patch_dir.mkdir(parents=True, exist_ok=True)
        self.prompt_dir.mkdir(parents=True, exist_ok=True)
        self.log_path.write_text("")


@dataclass(frozen=True)
class RLPatcherStageInputs:
    config: RunConfig | None
    workspace_root: Path
    diagnostics_path: Path
    inference_dir: Path
    fixes_path: Path
    debug_path: Path
    rlpatcher_jar: Path
    source_root: Path
    compile_inputs: CompileInputs | None
    paths: RLPatcherStagePaths


@dataclass(frozen=True)
class MatchedFixWarning:
    index: int
    fix: FixSuggestion
    warning: CheckerWarning
    prompt_path: Path


@dataclass(frozen=True)
class HarvestedPatch:
    match: MatchedFixWarning
    patch_text: str


@dataclass(frozen=True)
class RLPatcherStageInvocation:
    inputs: RLPatcherStageInputs
    harvested_patches: tuple[HarvestedPatch, ...]


@dataclass(frozen=True)
class MaterializedPatch:
    patch_path: Path
    changed_files: tuple[str, ...]
    preimage_hashes: dict[str, str]
    applied: bool


@dataclass(frozen=True)
class RLPatcherStageOutputs:
    paths: RLPatcherStagePaths
    materialized_patches: tuple[MaterializedPatch, ...]
    note: str

    @property
    def applied_changed_files(self) -> list[str]:
        changed: list[str] = []
        for patch in self.materialized_patches:
            if patch.applied:
                changed.extend(path for path in patch.changed_files if path not in changed)
        return changed


class RLPatcherStageWrapper(BaseStageWrapper):
    name = _STAGE_NAME

    def run(
        self,
        *,
        config: RunConfig | None = None,
        workspace_root: Path,
        diagnostics_path: Path,
        inference_dir: Path,
        fixes_path: Path,
        debug_path: Path,
        stage_output_dir: Path,
        rlpatcher_jar: Path,
        source_root: Path,
        compile_inputs: CompileInputs | None = None,
    ) -> StageResult:
        normalized_inputs = RLPatcherStageInputs(
            config=config,
            workspace_root=workspace_root.resolve(),
            diagnostics_path=diagnostics_path.resolve(),
            inference_dir=inference_dir.resolve(),
            fixes_path=fixes_path.resolve(),
            debug_path=debug_path.resolve(),
            rlpatcher_jar=rlpatcher_jar.resolve(),
            source_root=source_root.resolve(),
            compile_inputs=compile_inputs,
            paths=RLPatcherStagePaths.for_stage(stage_output_dir),
        )
        outputs = super().run(inputs=normalized_inputs)
        result = self._create_stage_result(outputs)
        write_stage_result(normalized_inputs.paths.root, result)
        return result

    def validate_inputs(self, **kwargs: object) -> None:
        inputs = self._stage_inputs(kwargs)
        _require_directory(inputs.workspace_root, "workspace root")
        _require_file(inputs.diagnostics_path, "diagnostics file")
        _require_directory(inputs.inference_dir, "inference directory")
        _require_file(inputs.fixes_path, "RLFixer fixes file")
        _require_file(inputs.debug_path, "RLFixer debug file")
        _require_file(inputs.rlpatcher_jar, "RLPatcher jar")
        inputs.paths.ensure()

    def invoke_tool(self, **kwargs: object) -> RLPatcherStageInvocation:
        inputs = self._stage_inputs(kwargs)
        matched = _matched_fix_warnings(
            diagnostics_path=inputs.diagnostics_path,
            fixes_path=inputs.fixes_path,
            debug_path=inputs.debug_path,
            source_root=inputs.source_root,
        )
        if not matched:
            return RLPatcherStageInvocation(inputs=inputs, harvested_patches=())

        harvested_patches: list[HarvestedPatch] = []
        for index, (fix, warning) in enumerate(matched, start=1):
            match = self._write_prompt(inputs=inputs, index=index, fix=fix, warning=warning)
            patch_text = self._invoke_patcher(inputs=inputs, match=match)
            harvested_patches.append(HarvestedPatch(match=match, patch_text=patch_text))

        return RLPatcherStageInvocation(inputs=inputs, harvested_patches=tuple(harvested_patches))

    def normalize_outputs(self, tool_result: object, **kwargs: object) -> RLPatcherStageOutputs:
        invocation = self._stage_invocation(tool_result)
        if not invocation.harvested_patches:
            invocation.inputs.paths.manifest_path.write_text(
                json.dumps({"stage": _STAGE_NAME, "patches": []}, indent=2, sort_keys=True) + "\n"
            )
            invocation.inputs.paths.log_path.write_text("No matched RLFixer materializations were available.\n")
            return RLPatcherStageOutputs(
                paths=invocation.inputs.paths,
                materialized_patches=(),
                note="No matched RLFixer materializations were available.",
            )

        materialized_patches: list[MaterializedPatch] = []
        manifest_entries: list[dict[str, object]] = []
        skipped = 0

        for harvested in invocation.harvested_patches:
            # Normalize tool-emitted headers before exposing any patch bundle artifact.
            normalized_patch, changed_files = _normalize_rlpatcher_patch(
                harvested.patch_text,
                workspace_root=invocation.inputs.workspace_root,
            )
            changed_files_tuple = tuple(changed_files)
            preimage_hashes = _compute_preimage_hashes(
                invocation.inputs.workspace_root,
                changed_files,
            )
            patch_name = _patch_filename(
                index=harvested.match.index,
                changed_files=changed_files,
                line_number=harvested.match.fix.line_number,
            )
            patch_path = invocation.inputs.paths.patch_dir / patch_name
            patch_path.write_text(normalized_patch)

            # Every patch was materialized against the same workspace state; apply them in
            # order and skip any that no longer applies cleanly after an earlier one.
            applied = _apply_to_workspace(
                workspace_root=invocation.inputs.workspace_root,
                patch_path=patch_path,
                log_path=invocation.inputs.paths.log_path,
            )
            if not applied:
                skipped += 1

            materialized_patch = MaterializedPatch(
                patch_path=patch_path,
                changed_files=changed_files_tuple,
                preimage_hashes=preimage_hashes,
                applied=applied,
            )
            materialized_patches.append(materialized_patch)
            manifest_entries.append(
                {
                    "patch_file": str(patch_path.resolve()),
                    "stage": _STAGE_NAME,
                    "strip_level": 0,
                    "target_root": ".",
                    "changed_files": list(changed_files_tuple),
                    "preimage_hashes": preimage_hashes,
                    "applied_to_workspace": applied,
                }
            )

        invocation.inputs.paths.manifest_path.write_text(
            json.dumps({"stage": _STAGE_NAME, "patches": manifest_entries}, indent=2, sort_keys=True) + "\n"
        )
        return RLPatcherStageOutputs(
            paths=invocation.inputs.paths,
            materialized_patches=tuple(materialized_patches),
            note=(
                f"Materialized {len(materialized_patches)} patch(es); applied "
                f"{len(materialized_patches) - skipped}, skipped {skipped} that conflicted with earlier patches."
            ),
        )

    def validate_outputs(self, normalized_result: object, **kwargs: object) -> None:
        outputs = self._stage_outputs(normalized_result)
        if not outputs.paths.manifest_path.is_file():
            raise StageExecutionError(
                f"RLPatcher stage did not produce a patch manifest: {outputs.paths.manifest_path}"
            )
        if not outputs.paths.patch_dir.is_dir():
            raise StageExecutionError(
                f"RLPatcher stage did not preserve its patch directory: {outputs.paths.patch_dir}"
            )
        if outputs.paths.raw_patch_path.exists():
            raise StageExecutionError(
                f"RLPatcher stage leaked a raw patch artifact: {outputs.paths.raw_patch_path}"
            )

        for patch in outputs.materialized_patches:
            if not patch.patch_path.is_file():
                raise StageExecutionError(
                    f"RLPatcher stage did not produce materialized patch artifact: {patch.patch_path}"
                )
            if not patch.changed_files:
                raise StageExecutionError("RLPatcher stage produced a patch entry without changed_files.")
            if set(patch.changed_files) != set(patch.preimage_hashes):
                raise StageExecutionError("RLPatcher stage produced inconsistent preimage hashes.")

    def _write_prompt(
        self,
        *,
        inputs: RLPatcherStageInputs,
        index: int,
        fix: FixSuggestion,
        warning: CheckerWarning,
    ) -> MatchedFixWarning:
        prompt_path = inputs.paths.prompt_dir / f"prompt-{index:04d}.json"
        prompt_path.write_text(build_rlpatcher_prompt(warning, fix) + "\n")
        return MatchedFixWarning(index=index, fix=fix, warning=warning, prompt_path=prompt_path)

    def _invoke_patcher(
        self,
        *,
        inputs: RLPatcherStageInputs,
        match: MatchedFixWarning,
    ) -> str:
        inputs.paths.raw_patch_path.unlink(missing_ok=True)
        command = [
            java_executable(),
            *(inputs.compile_inputs.java_properties() if inputs.compile_inputs is not None else []),
            "-jar",
            str(inputs.rlpatcher_jar),
            "--prompt",
            str(match.prompt_path),
            "--project-root",
            str(inputs.workspace_root),
        ]
        completed = run_stage_command(command=command, cwd=inputs.paths.root)
        append_command_log(
            inputs.paths.log_path,
            title=f"rlpatcher_{match.index:04d}",
            command=command,
            completed=completed,
            tool_name=self.name,
            timeout_seconds=stage_timeout_seconds(inputs.config),
        )

        if completed.returncode != 0:
            raise StageExecutionError(f"RLPatcher command failed. See log: {inputs.paths.log_path}")
        if _PATCH_SUCCESS_TEXT not in completed.stdout:
            raise StageExecutionError(f"RLPatcher did not report success. See log: {inputs.paths.log_path}")
        if not inputs.paths.raw_patch_path.is_file():
            raise StageExecutionError(f"RLPatcher did not emit rlfixer.patch. See log: {inputs.paths.log_path}")

        patch_text = inputs.paths.raw_patch_path.read_text()
        inputs.paths.raw_patch_path.unlink(missing_ok=True)
        return patch_text

    def _create_stage_result(self, outputs: RLPatcherStageOutputs) -> StageResult:
        changed_files = outputs.applied_changed_files
        return StageResult(
            stage=_STAGE_NAME,
            changed=bool(changed_files),
            changed_files=changed_files,
            rerun_required=bool(changed_files),
            artifacts={
                "log": str(outputs.paths.log_path),
                "patch_manifest": str(outputs.paths.manifest_path),
                "patch_dir": str(outputs.paths.patch_dir),
            },
            notes=[outputs.note],
            success=True,
        )

    def _stage_inputs(self, kwargs: dict[str, object]) -> RLPatcherStageInputs:
        inputs = kwargs["inputs"]
        if not isinstance(inputs, RLPatcherStageInputs):
            raise StageExecutionError("RLPatcher stage received invalid normalized inputs.")
        return inputs

    def _stage_invocation(self, tool_result: object) -> RLPatcherStageInvocation:
        if not isinstance(tool_result, RLPatcherStageInvocation):
            raise StageExecutionError("RLPatcher stage received invalid tool invocation outputs.")
        return tool_result

    def _stage_outputs(self, normalized_result: object) -> RLPatcherStageOutputs:
        if not isinstance(normalized_result, RLPatcherStageOutputs):
            raise StageExecutionError("RLPatcher stage received invalid normalized outputs.")
        return normalized_result


def run_rlpatcher_stage(
    *,
    config: RunConfig | None = None,
    workspace_root: Path,
    diagnostics_path: Path,
    inference_dir: Path,
    fixes_path: Path,
    debug_path: Path,
    stage_output_dir: Path,
    rlpatcher_jar: Path,
    source_root: Path,
    compile_inputs: CompileInputs | None = None,
) -> StageResult:
    return _WRAPPER.run(
        config=config,
        workspace_root=workspace_root,
        diagnostics_path=diagnostics_path,
        inference_dir=inference_dir,
        fixes_path=fixes_path,
        debug_path=debug_path,
        stage_output_dir=stage_output_dir,
        rlpatcher_jar=rlpatcher_jar,
        source_root=source_root,
        compile_inputs=compile_inputs,
    )


def _matched_fix_warnings(
    *,
    diagnostics_path: Path,
    fixes_path: Path,
    debug_path: Path,
    source_root: Path,
) -> list[tuple[FixSuggestion, CheckerWarning]]:
    warnings = parse_checker_warnings(diagnostics_path.read_text(errors="replace"))
    suggestions = parse_fix_suggestions(fixes_path.read_text(errors="replace"), source_root=source_root)
    fixable_keys = parse_debug_fixable(debug_path.read_text(errors="replace"))
    return match_fixes_to_warnings(select_fixable_suggestions(suggestions, fixable_keys), warnings)


def _apply_to_workspace(*, workspace_root: Path, patch_path: Path, log_path: Path) -> bool:
    try:
        check = run_patch(
            cwd=workspace_root,
            patch_path=patch_path,
            strip_level=0,
            check_only=True,
            require_gnu=True,
            operation_label="rlpatcher workspace dry-run",
            forward=True,
            ignore_whitespace=True,
        )
    except PatchToolError as exc:
        raise StageExecutionError(str(exc)) from exc
    append_patch_execution_log(log_path, title=f"dry_run:{patch_path.name}", execution=check)
    if check.completed.returncode != 0:
        return False

    applied = apply_normalized_patch(workspace_root=workspace_root, patch_path=patch_path)
    append_patch_execution_log(log_path, title=f"apply:{patch_path.name}", execution=applied)
    if applied.completed.returncode != 0:
        raise StageExecutionError(f"Patch {patch_path.name} passed its dry run but failed to apply. See log: {log_path}")
    return True


def _normalize_rlpatcher_patch(patch_text: str, *, workspace_root: Path) -> tuple[str, list[str]]:
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


def _compute_preimage_hashes(workspace_root: Path, changed_files: list[str]) -> dict[str, str]:
    hashes: dict[str, str] = {}
    for changed_file in changed_files:
        file_path = workspace_root / changed_file
        if not file_path.is_file():
            raise StageExecutionError(f"Cannot compute preimage hash for missing workspace file: {file_path}")
        hashes[changed_file] = hashlib.sha256(file_path.read_bytes()).hexdigest()
    return hashes


def _patch_filename(*, index: int, changed_files: list[str], line_number: int) -> str:
    safe_path = changed_files[0].replace("/", "_").replace("\\", "_")
    return f"patch-{index:04d}-{safe_path}-L{line_number}.patch"


def _require_file(path: Path, label: str) -> Path:
    resolved = path.resolve()
    if not resolved.is_file():
        raise StageExecutionError(f"Missing {label}: {resolved}")
    return resolved


def _require_directory(path: Path, label: str) -> Path:
    resolved = path.resolve()
    if not resolved.is_dir():
        raise StageExecutionError(f"Missing {label}: {resolved}")
    return resolved


_WRAPPER = RLPatcherStageWrapper()


__all__ = ["RLPatcherStageWrapper", "StageExecutionError", "run_rlpatcher_stage"]
