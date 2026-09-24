from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
from pathlib import Path

from arodnap.contracts import RunConfig
from arodnap.orchestrator.workspace import copied_workspace
from arodnap.patch_tool import PatchExecution, PatchToolError, append_patch_execution_log, run_patch


class ApplyError(RuntimeError):
    """Raised when a patch bundle cannot be validated or applied safely."""


@dataclass(frozen=True)
class PatchEntry:
    patch_file: Path
    stage: str
    strip_level: int
    target_root: str
    changed_files: list[str]
    preimage_hashes: dict[str, str]


def apply_patch_bundle(config: RunConfig) -> None:
    if config.patch_dir is None:
        raise ApplyError("Patch directory is required for apply.")
    patch_dir = config.patch_dir.resolve()
    if not patch_dir.is_dir():
        raise ApplyError(f"Patch directory does not exist: {patch_dir}")
    if not config.repo_root.is_dir():
        raise ApplyError(f"Target repo does not exist: {config.repo_root}")

    manifest_path = _find_manifest_path(patch_dir)
    entries = _load_manifest(manifest_path, patch_dir=patch_dir)
    log_path = _resolve_apply_log_path(config)
    _reset_apply_log(log_path)
    _validate_preimages(config.repo_root, entries)

    with copied_workspace(config.repo_root, keep_workspace=config.keep_workspace) as workspace:
        _apply_entries(workspace.workspace_root, entries, check_only=True, log_path=log_path)

    _validate_preimages(config.repo_root, entries)
    _apply_entries(config.repo_root, entries, check_only=False, log_path=log_path)


def _find_manifest_path(patch_dir: Path) -> Path:
    candidates = [
        patch_dir / "manifest.json",
        patch_dir / "patch_manifest.json",
    ]
    if patch_dir.name == "patches":
        candidates.extend(
            [
                patch_dir.parent / "manifest.json",
                patch_dir.parent / "patch_manifest.json",
            ]
        )

    for candidate in candidates:
        if candidate.is_file():
            return candidate.resolve()

    raise ApplyError(f"Patch manifest not found under patch directory: {patch_dir}")


def _load_manifest(manifest_path: Path, *, patch_dir: Path) -> list[PatchEntry]:
    try:
        payload = json.loads(manifest_path.read_text())
    except json.JSONDecodeError as exc:
        raise ApplyError(f"Malformed patch manifest JSON: {manifest_path}") from exc

    if not isinstance(payload, dict):
        raise ApplyError(f"Malformed patch manifest payload: {manifest_path}")

    raw_entries = payload.get("patches")
    if not isinstance(raw_entries, list):
        raise ApplyError(f"Patch manifest must contain a 'patches' list: {manifest_path}")

    return [_parse_patch_entry(entry, patch_dir=patch_dir, manifest_path=manifest_path) for entry in raw_entries]


def _parse_patch_entry(entry: object, *, patch_dir: Path, manifest_path: Path) -> PatchEntry:
    if not isinstance(entry, dict):
        raise ApplyError(f"Patch manifest entry must be an object: {manifest_path}")

    patch_file = _resolve_patch_file(entry.get("patch_file"), patch_dir=patch_dir, manifest_path=manifest_path)
    stage = entry.get("stage")
    strip_level = entry.get("strip_level")
    target_root = entry.get("target_root")
    changed_files = entry.get("changed_files")
    preimage_hashes = entry.get("preimage_hashes")

    if not isinstance(stage, str) or not stage:
        raise ApplyError(f"Patch manifest entry is missing a valid stage: {manifest_path}")
    if not isinstance(strip_level, int) or strip_level < 0:
        raise ApplyError(f"Patch manifest entry is missing a valid strip_level: {manifest_path}")
    if not isinstance(target_root, str) or not target_root:
        raise ApplyError(f"Patch manifest entry is missing a valid target_root: {manifest_path}")
    if not isinstance(changed_files, list) or not all(isinstance(item, str) and item for item in changed_files):
        raise ApplyError(f"Patch manifest entry is missing valid changed_files: {manifest_path}")
    if not isinstance(preimage_hashes, dict) or not all(
        isinstance(key, str) and isinstance(value, str) for key, value in preimage_hashes.items()
    ):
        raise ApplyError(f"Patch manifest entry is missing valid preimage_hashes: {manifest_path}")

    normalized_target_root = _normalize_relative_path(target_root, label="target_root")
    normalized_changed_files = [
        _normalize_relative_path(changed_file, label="changed_files entry")
        for changed_file in changed_files
    ]
    if set(preimage_hashes) != set(normalized_changed_files):
        raise ApplyError(
            f"Patch manifest entry preimage hashes do not match changed_files: {manifest_path}"
        )

    return PatchEntry(
        patch_file=patch_file,
        stage=stage,
        strip_level=strip_level,
        target_root=normalized_target_root,
        changed_files=normalized_changed_files,
        preimage_hashes={key: preimage_hashes[key] for key in normalized_changed_files},
    )


def _resolve_patch_file(value: object, *, patch_dir: Path, manifest_path: Path) -> Path:
    if not isinstance(value, str) or not value:
        raise ApplyError(f"Patch manifest entry is missing a valid patch_file: {manifest_path}")
    candidate = Path(value)
    resolved = candidate.resolve() if candidate.is_absolute() else (patch_dir / candidate).resolve()
    if not resolved.is_file():
        raise ApplyError(f"Patch file does not exist: {resolved}")
    return resolved


def _normalize_relative_path(path_text: str, *, label: str) -> str:
    if path_text == ".":
        return path_text
    path = Path(path_text)
    if path.is_absolute() or ".." in path.parts:
        raise ApplyError(f"Unsupported {label} outside repo root: {path_text}")
    normalized = path.as_posix()
    if not normalized:
        raise ApplyError(f"Unsupported empty {label}.")
    return normalized


def _validate_preimages(repo_root: Path, entries: list[PatchEntry]) -> None:
    for entry in entries:
        target_dir = _resolve_target_dir(repo_root, entry)
        if not target_dir.is_dir():
            raise ApplyError(f"Patch target root does not exist: {target_dir}")
        for changed_file in entry.changed_files:
            file_path = repo_root / changed_file
            if not file_path.is_file():
                raise ApplyError(f"Patch target file does not exist: {file_path}")
            observed_hash = hashlib.sha256(file_path.read_bytes()).hexdigest()
            expected_hash = entry.preimage_hashes[changed_file]
            if observed_hash != expected_hash:
                raise ApplyError(
                    f"Preimage hash mismatch for {changed_file}: expected {expected_hash}, got {observed_hash}"
                )


def _apply_entries(
    repo_root: Path,
    entries: list[PatchEntry],
    *,
    check_only: bool,
    log_path: Path,
) -> None:
    for entry in entries:
        target_dir = _resolve_target_dir(repo_root, entry)
        try:
            execution = run_patch(
                cwd=target_dir,
                patch_path=entry.patch_file,
                strip_level=entry.strip_level,
                check_only=check_only,
                require_gnu=False,
                operation_label=("apply dry-run validation" if check_only else "apply"),
            )
        except PatchToolError as exc:
            raise ApplyError(str(exc)) from exc

        append_patch_execution_log(
            log_path,
            title=f"{'apply_dry_run' if check_only else 'apply_patch'}:{entry.stage}",
            execution=execution,
        )

        if execution.completed.returncode != 0:
            mode = "dry-run validation" if check_only else "apply"
            details = _format_patch_failure(entry=entry, execution=execution, mode=mode)
            raise ApplyError(details)


def _resolve_target_dir(repo_root: Path, entry: PatchEntry) -> Path:
    return repo_root if entry.target_root == "." else (repo_root / entry.target_root).resolve()


def _format_patch_failure(
    *,
    entry: PatchEntry,
    execution: PatchExecution,
    mode: str,
) -> str:
    stderr = execution.completed.stderr.strip()
    stdout = execution.completed.stdout.strip()
    output = stderr or stdout or "<no patch output>"
    return (
        f"Patch {mode} failed for {entry.patch_file} "
        f"(stage={entry.stage}, strip_level={entry.strip_level}, target_root={entry.target_root}, "
        f"patch_tool={execution.tool.version}): {output}"
    )


def _resolve_apply_log_path(config: RunConfig) -> Path:
    return config.out_dir.resolve() / "logs" / "apply.log"


def _reset_apply_log(log_path: Path) -> None:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_path.write_text("")


__all__ = ["ApplyError", "PatchEntry", "apply_patch_bundle"]
