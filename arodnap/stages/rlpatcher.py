from __future__ import annotations

import hashlib
import json
from pathlib import Path
import subprocess

from RLPatcherRunner import (
    build_prompt_json,
    get_checkerframework_warnings,
    match_fixes_to_warnings,
    parse_fix_suggestions,
    parse_rlfixer_debug_fixable,
)
from arodnap.contracts import StageResult

from .base import StageExecutionError, append_command_log, write_stage_result

_STAGE_NAME = "rlpatcher"
_PATCH_SUCCESS_TEXT = "Patch applied successfully"


def run_rlpatcher_stage(
    *,
    workspace_root: Path,
    diagnostics_path: Path,
    inference_dir: Path,
    fixes_path: Path,
    debug_path: Path,
    stage_output_dir: Path,
    rlpatcher_jar: Path,
) -> StageResult:
    workspace_root = _require_directory(workspace_root, "workspace root")
    diagnostics_path = _require_file(diagnostics_path, "diagnostics file")
    inference_dir = _require_directory(inference_dir, "inference directory")
    fixes_path = _require_file(fixes_path, "RLFixer fixes file")
    debug_path = _require_file(debug_path, "RLFixer debug file")
    rlpatcher_jar = _require_file(rlpatcher_jar, "RLPatcher jar")
    stage_output_dir = stage_output_dir.resolve()
    stage_output_dir.mkdir(parents=True, exist_ok=True)

    log_path = stage_output_dir / "stage.log"
    patch_dir = stage_output_dir / "patches"
    prompt_dir = stage_output_dir / "_tmp_prompts"
    manifest_path = stage_output_dir / "patch_manifest.json"
    patch_dir.mkdir(parents=True, exist_ok=True)
    prompt_dir.mkdir(parents=True, exist_ok=True)
    log_path.write_text("")

    matched = _matched_fix_warnings(
        diagnostics_path=diagnostics_path,
        fixes_path=fixes_path,
        debug_path=debug_path,
    )
    if not matched:
        return _write_noop_result(
            manifest_path=manifest_path,
            patch_dir=patch_dir,
            log_path=log_path,
            stage_output_dir=stage_output_dir,
            note="No matched RLFixer materializations were available.",
        )

    manifest_entries: list[dict[str, object]] = []

    for index, (fix, warning) in enumerate(matched, start=1):
        prompt_path = prompt_dir / f"prompt-{index:04d}.json"
        prompt_path.write_text(
            build_prompt_json(
                cf_warning_block=warning["message"],
                rlfixer_hint_block=fix["suggestion"],
            )
            + "\n"
        )

        raw_patch_path = stage_output_dir / "rlfixer.patch"
        raw_patch_path.unlink(missing_ok=True)
        command = [
            "java",
            "-jar",
            str(rlpatcher_jar),
            "--prompt",
            str(prompt_path),
            "--project-root",
            str(workspace_root),
        ]
        completed = subprocess.run(
            command,
            cwd=stage_output_dir,
            capture_output=True,
            text=True,
            check=False,
        )
        append_command_log(
            log_path,
            title=f"rlpatcher_{index:04d}",
            command=command,
            completed=completed,
        )

        if completed.returncode != 0:
            raise StageExecutionError(f"RLPatcher command failed. See log: {log_path}")
        if _PATCH_SUCCESS_TEXT not in completed.stdout:
            raise StageExecutionError(f"RLPatcher did not report success. See log: {log_path}")
        if not raw_patch_path.is_file():
            raise StageExecutionError(f"RLPatcher did not emit rlfixer.patch. See log: {log_path}")

        normalized_patch, changed_files = _normalize_rlpatcher_patch(
            raw_patch_path.read_text(),
            workspace_root=workspace_root,
        )
        preimage_hashes = _compute_preimage_hashes(workspace_root, changed_files)
        patch_name = _patch_filename(index=index, changed_files=changed_files, line_number=fix["line_number"])
        output_patch_path = patch_dir / patch_name
        output_patch_path.write_text(normalized_patch)
        raw_patch_path.unlink(missing_ok=True)

        manifest_entries.append(
            {
                "patch_file": str(output_patch_path.resolve()),
                "stage": _STAGE_NAME,
                "strip_level": 0,
                "target_root": ".",
                "changed_files": changed_files,
                "preimage_hashes": preimage_hashes,
            }
        )

    manifest = {"stage": _STAGE_NAME, "patches": manifest_entries}
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")

    result = StageResult(
        stage=_STAGE_NAME,
        changed=False,
        changed_files=[],
        rerun_required=False,
        artifacts={
            "log": str(log_path),
            "patch_manifest": str(manifest_path),
            "patch_dir": str(patch_dir),
        },
        notes=[f"Materialized {len(manifest_entries)} normalized patch(es)."],
        success=True,
    )
    write_stage_result(stage_output_dir, result)
    return result


def _matched_fix_warnings(
    *,
    diagnostics_path: Path,
    fixes_path: Path,
    debug_path: Path,
) -> list[tuple[dict, dict]]:
    cf_warnings = get_checkerframework_warnings(diagnostics_path.read_text(errors="replace"))
    all_fixes = parse_fix_suggestions(fixes_path.read_text(errors="replace"))
    debug_text = debug_path.read_text(errors="replace")
    fixable_keys = parse_rlfixer_debug_fixable(debug_text) if debug_text.strip() else set()
    fixes = [item for item in all_fixes if (item["relpath"], item["line_number"]) in fixable_keys]
    if not fixable_keys:
        fixes = all_fixes
    return match_fixes_to_warnings(fixes, cf_warnings)


def _write_noop_result(
    *,
    manifest_path: Path,
    patch_dir: Path,
    log_path: Path,
    stage_output_dir: Path,
    note: str,
) -> StageResult:
    manifest_path.write_text(json.dumps({"stage": _STAGE_NAME, "patches": []}, indent=2, sort_keys=True) + "\n")
    log_path.write_text(note + "\n")
    result = StageResult(
        stage=_STAGE_NAME,
        changed=False,
        changed_files=[],
        rerun_required=False,
        artifacts={
            "log": str(log_path),
            "patch_manifest": str(manifest_path),
            "patch_dir": str(patch_dir),
        },
        notes=[note],
        success=True,
    )
    write_stage_result(stage_output_dir, result)
    return result


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


__all__ = ["StageExecutionError", "run_rlpatcher_stage"]
