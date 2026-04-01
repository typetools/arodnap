from __future__ import annotations

from dataclasses import dataclass
import json
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

_FIXED_ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)


class RLFixerCompatibilityBundleError(RuntimeError):
    """Raised when the RLFixer compatibility bundle cannot be generated safely."""


@dataclass(frozen=True)
class RLFixerCompatibilityBundle:
    root: Path
    info_dir: Path
    classes_file: Path
    sources_file: Path
    jar_dir: Path
    jar_path: Path
    metadata_path: Path


def generate_rlfixer_compatibility_bundle(
    *,
    workspace_root: Path,
    source_files_file: Path,
    app_classes_file: Path,
    classpath_entries_file: Path,
    compiled_outputs_root: Path,
    stage_output_dir: Path,
) -> RLFixerCompatibilityBundle:
    workspace_root = _require_directory(workspace_root, "workspace root")
    source_files_file = _require_file(source_files_file, "source files file")
    app_classes_file = _require_file(app_classes_file, "app classes file")
    classpath_entries_file = _require_file(classpath_entries_file, "classpath entries file")
    compiled_outputs_root = _require_directory(compiled_outputs_root, "compiled outputs root")

    source_paths = _load_source_paths(source_files_file, workspace_root=workspace_root)
    app_classes = _load_nonempty_lines(app_classes_file, label="app classes file")
    classpath_entries = _load_classpath_entries(classpath_entries_file)

    bundle_root = stage_output_dir.resolve() / "compat_bundle"
    info_dir = bundle_root / "info"
    jar_dir = bundle_root / "jarfile"
    classes_file = info_dir / "classes"
    sources_file = info_dir / "sources"
    jar_path = jar_dir / f"{workspace_root.name}.jar"
    metadata_path = bundle_root / "metadata.json"

    info_dir.mkdir(parents=True, exist_ok=True)
    jar_dir.mkdir(parents=True, exist_ok=True)

    classes_file.write_text("\n".join(app_classes) + "\n")
    sources_file.write_text("\n".join(source_paths) + "\n")

    _write_compatibility_jar(
        jar_path=jar_path,
        compiled_outputs_root=compiled_outputs_root,
        classpath_entries=classpath_entries,
    )

    metadata = {
        "workspace_root": str(workspace_root),
        "bundle_root": str(bundle_root),
        "project_name": workspace_root.name,
        "classes_file": str(classes_file),
        "sources_file": str(sources_file),
        "jar_path": str(jar_path),
        "compiled_outputs_root": str(compiled_outputs_root),
        "classpath_entries_file": str(classpath_entries_file),
        "classpath_entries": [str(entry) for entry in classpath_entries],
    }
    metadata_path.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n")

    return RLFixerCompatibilityBundle(
        root=bundle_root,
        info_dir=info_dir,
        classes_file=classes_file,
        sources_file=sources_file,
        jar_dir=jar_dir,
        jar_path=jar_path,
        metadata_path=metadata_path,
    )


def _load_source_paths(source_files_file: Path, *, workspace_root: Path) -> list[str]:
    raw_source_paths = _load_nonempty_lines(source_files_file, label="source files file")
    normalized: list[str] = []
    for line in raw_source_paths:
        source_path = Path(line)
        if source_path.is_absolute():
            try:
                relative = source_path.resolve().relative_to(workspace_root)
            except ValueError as exc:
                raise RLFixerCompatibilityBundleError(
                    f"Source path {source_path} does not live under workspace root {workspace_root}."
                ) from exc
        else:
            relative = source_path
            if relative.is_absolute() or ".." in relative.parts:
                raise RLFixerCompatibilityBundleError(f"Unsupported relative source path: {line}")
        normalized.append(relative.as_posix())
    return normalized


def _load_classpath_entries(classpath_entries_file: Path) -> list[Path]:
    entries = [Path(line) for line in _load_nonempty_lines(classpath_entries_file, label="classpath entries file")]
    for entry in entries:
        if not entry.exists():
            raise RLFixerCompatibilityBundleError(f"Missing classpath entry: {entry}")
        if not (entry.is_dir() or (entry.is_file() and entry.suffix == ".jar")):
            raise RLFixerCompatibilityBundleError(
                f"Unsupported classpath entry for compatibility bundle: {entry}"
            )
    return entries


def _load_nonempty_lines(path: Path, *, label: str) -> list[str]:
    lines = [line.strip() for line in path.read_text().splitlines() if line.strip()]
    if not lines:
        raise RLFixerCompatibilityBundleError(f"{label.capitalize()} is empty: {path}")
    return lines


def _write_compatibility_jar(
    *,
    jar_path: Path,
    compiled_outputs_root: Path,
    classpath_entries: list[Path],
) -> None:
    entries: dict[str, bytes] = {}
    _collect_directory_entries(entries, compiled_outputs_root)

    for entry in classpath_entries:
        resolved_entry = entry.resolve()
        if resolved_entry == compiled_outputs_root.resolve():
            continue
        if resolved_entry.is_dir():
            _collect_directory_entries(entries, resolved_entry)
        else:
            _collect_jar_entries(entries, resolved_entry)

    if not entries:
        raise RLFixerCompatibilityBundleError(
            f"No jar contents were collected for compatibility bundle at {jar_path}"
        )

    with ZipFile(jar_path, "w", compression=ZIP_DEFLATED) as archive:
        for archive_name in sorted(entries):
            archive.writestr(_zip_info(archive_name), entries[archive_name])


def _collect_directory_entries(entries: dict[str, bytes], root: Path) -> None:
    for file_path in sorted(path for path in root.rglob("*") if path.is_file()):
        archive_name = file_path.relative_to(root).as_posix()
        entries.setdefault(archive_name, file_path.read_bytes())


def _collect_jar_entries(entries: dict[str, bytes], jar_path: Path) -> None:
    with ZipFile(jar_path) as archive:
        for archive_name in sorted(archive.namelist()):
            if archive_name.endswith("/") or _should_skip_archive_entry(archive_name):
                continue
            entries.setdefault(archive_name, archive.read(archive_name))


def _should_skip_archive_entry(archive_name: str) -> bool:
    upper_name = archive_name.upper()
    if upper_name == "META-INF/MANIFEST.MF":
        return True
    return upper_name.startswith("META-INF/") and upper_name.endswith((".SF", ".RSA", ".DSA"))


def _zip_info(archive_name: str) -> ZipInfo:
    info = ZipInfo(archive_name, date_time=_FIXED_ZIP_TIMESTAMP)
    info.compress_type = ZIP_DEFLATED
    return info


def _require_file(path: Path, label: str) -> Path:
    resolved = path.resolve()
    if not resolved.is_file():
        raise RLFixerCompatibilityBundleError(f"Missing {label}: {resolved}")
    return resolved


def _require_directory(path: Path, label: str) -> Path:
    resolved = path.resolve()
    if not resolved.is_dir():
        raise RLFixerCompatibilityBundleError(f"Missing {label}: {resolved}")
    return resolved


__all__ = [
    "RLFixerCompatibilityBundle",
    "RLFixerCompatibilityBundleError",
    "generate_rlfixer_compatibility_bundle",
]
