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
    StageTimeoutError,
    append_command_log,
    apply_normalized_patch,
    normalize_unified_diff_paths,
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
    has_nothing_to_do,
    parse_fix_suggestions,
    select_fixable_suggestions,
)

_STAGE_NAME = "rlpatcher"
_PATCH_SUCCESS_TEXT = "Patch applied successfully"
_PATCH_REJECTED_TEXT = "Patch failed"
_PATCH_UNSAFE_TEXT = "Patch not materialized (unsafe edit)"
# Per-fix outcomes of running RLPatcher on one RLFixer suggestion.
MATERIALIZED = "materialized"  # RLPatcher produced a patch that passed its compile check
NO_CHANGE = "no_change"  # RLFixer or RLPatcher found nothing to change for this suggestion
REJECTED = "rejected"  # RLPatcher's edit did not pass its compile check
UNSAFE = "unsafe"  # RLPatcher could not place the fix without changing what the code does
UNSUPPORTED = "unsupported"  # RLPatcher does not handle this kind of suggestion
CRASHED = "crashed"  # RLPatcher exited with an error
TIMED_OUT = "timed_out"  # RLPatcher ran longer than --stage-timeout and was stopped


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
    outcome: str
    patch_text: str | None


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
            outcome, patch_text = self._invoke_patcher(inputs=inputs, match=match)
            harvested_patches.append(HarvestedPatch(match=match, outcome=outcome, patch_text=patch_text))

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
        fix_outcomes = [
            {
                "index": harvested.match.index,
                "file": harvested.match.fix.relpath,
                "line": harvested.match.fix.line_number,
                "outcome": harvested.outcome,
            }
            for harvested in invocation.harvested_patches
        ]

        for harvested in invocation.harvested_patches:
            if harvested.outcome != MATERIALIZED:
                continue
            # Normalize tool-emitted headers before exposing any patch bundle artifact.
            normalized_patch, changed_files = normalize_unified_diff_paths(
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
            json.dumps(
                {"stage": _STAGE_NAME, "patches": manifest_entries, "fixes": fix_outcomes},
                indent=2,
                sort_keys=True,
            )
            + "\n"
        )
        return RLPatcherStageOutputs(
            paths=invocation.inputs.paths,
            materialized_patches=tuple(materialized_patches),
            note=_outcome_note(fix_outcomes, applied=len(materialized_patches) - skipped, skipped=skipped),
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
    ) -> tuple[str, str | None]:
        if has_nothing_to_do(match.fix):
            # RLPatcher would fall back to a finally block built from the warning's
            # (possibly truncated) expression text, so it is not run at all.
            return NO_CHANGE, None
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
        # RLPatcher edits the source in place and writes its backup back afterwards, which can
        # change the file (e.g. add a trailing newline). The stage applies patches itself, so
        # the workspace must be byte-for-byte what it was before RLPatcher ran.
        touched = {Path(match.warning.filepath), Path(match.fix.filepath)}
        originals = {path: path.read_bytes() for path in touched if path.is_file()}
        try:
            completed = run_stage_command(
                command=command, cwd=inputs.paths.root, timeout_seconds=stage_timeout_seconds(inputs.config)
            )
        except StageTimeoutError as exc:
            with inputs.paths.log_path.open("a", encoding="utf-8") as handle:
                handle.write(f"== rlpatcher_{match.index:04d} ==\nTIMED_OUT: {exc}\n\n")
            inputs.paths.raw_patch_path.unlink(missing_ok=True)
            return TIMED_OUT, None
        finally:
            for path, content in originals.items():
                if not path.is_file() or path.read_bytes() != content:
                    path.write_bytes(content)
        append_command_log(
            inputs.paths.log_path,
            title=f"rlpatcher_{match.index:04d}",
            command=command,
            completed=completed,
            tool_name=self.name,
            timeout_seconds=stage_timeout_seconds(inputs.config),
        )

        # Each suggestion is independent: an outcome other than a patch is recorded for it
        # (see the stage notes and patch_manifest.json) and the other suggestions still run.
        raw_patch = inputs.paths.raw_patch_path
        patch_text = raw_patch.read_text() if raw_patch.is_file() else ""
        raw_patch.unlink(missing_ok=True)
        if completed.returncode != 0:
            return CRASHED, None
        if _PATCH_REJECTED_TEXT in completed.stdout:
            return REJECTED, None
        if _PATCH_UNSAFE_TEXT in completed.stdout:
            return UNSAFE, None
        if _PATCH_SUCCESS_TEXT not in completed.stdout:
            return UNSUPPORTED, None
        if not _changes_code(patch_text):
            return NO_CHANGE, None
        return MATERIALIZED, patch_text

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


def _changes_code(patch_text: str) -> bool:
    """False for an empty diff or one that only changes whitespace (RLPatcher reprints files)."""
    removed = [line[1:].strip() for line in patch_text.splitlines() if line.startswith("-") and not line.startswith("---")]
    added = [line[1:].strip() for line in patch_text.splitlines() if line.startswith("+") and not line.startswith("+++")]
    return [line for line in removed if line] != [line for line in added if line]


def _outcome_note(fix_outcomes: list[dict[str, object]], *, applied: int, skipped: int) -> str:
    counts = {outcome: 0 for outcome in (MATERIALIZED, NO_CHANGE, REJECTED, UNSAFE, UNSUPPORTED, CRASHED, TIMED_OUT)}
    for entry in fix_outcomes:
        counts[str(entry["outcome"])] += 1
    note = (
        f"RLPatcher materialized {counts[MATERIALIZED]} of {len(fix_outcomes)} RLFixer suggestion(s); "
        f"applied {applied}, skipped {skipped} that conflicted with earlier patches."
    )
    others = [
        f"{counts[outcome]} {label}"
        for outcome, label in (
            (NO_CHANGE, "needed no change"),
            (REJECTED, "failed RLPatcher's compile check"),
            (UNSAFE, "could not be placed without changing what the code does"),
            (UNSUPPORTED, "are not supported by RLPatcher"),
            (CRASHED, "crashed RLPatcher"),
            (TIMED_OUT, "exceeded --stage-timeout"),
        )
        if counts[outcome]
    ]
    return note + (f" Not materialized: {', '.join(others)}." if others else "")


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
