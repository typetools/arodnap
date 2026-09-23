"""Compile units recorded from a project's own build, and the analysis universe built from them.

Every capture backend (Gradle init script, Maven/command javac shim, Ant compiler adapter)
records javac invocations as JSON lines `{"cwd": ..., "args": [...]}`. This module turns
them into compile units and merges those into the single set of sources and classpath
Arodnap analyzes, so nothing downstream depends on the build system.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
import json
import os
from pathlib import Path

from .base import UnsupportedProjectError

# javac options whose value is the next argument (or follows "=" for the long forms).
_OPTIONS_WITH_VALUE = frozenset(
    {
        "-d", "-s", "-h",
        "-cp", "-classpath", "--class-path",
        "-sourcepath", "--source-path",
        "-processorpath", "--processor-path", "--processor-module-path",
        "-processor",
        "-encoding",
        "--release", "-source", "--source", "-target", "--target",
        "-bootclasspath", "--boot-class-path", "-extdirs", "--extension-dirs", "-endorseddirs",
        "--system", "--module-path", "-p", "--module-source-path", "--upgrade-module-path",
        "--add-modules", "--limit-modules", "--add-exports", "--add-reads", "--patch-module",
        "--module", "-m", "--module-version", "-profile",
        "-Xmaxerrs", "-Xmaxwarns", "--default-module-for-created-files",
    }
)
_LOMBOK_MARKERS = ("/org/projectlombok/", "/org.projectlombok/")


@dataclass(frozen=True)
class CompileUnit:
    """One javac invocation of the project's build."""

    cwd: Path
    sources: tuple[Path, ...]
    classpath: tuple[Path, ...]
    output_dir: Path | None
    generated_source_dir: Path | None
    release: int | None
    encoding: str | None
    processor_path: tuple[Path, ...]
    processors: tuple[str, ...]
    label: str | None = None


@dataclass(frozen=True)
class AnalysisInputs:
    """What Arodnap analyzes: every unit's sources compiled together."""

    units: tuple[CompileUnit, ...]
    sources: tuple[Path, ...]
    generated_sources: tuple[Path, ...]
    classpath: tuple[Path, ...]
    release: int | None
    encoding: str | None
    analysis_root: Path


def parse_javac_invocation(args: list[str], *, cwd: Path, label: str | None = None) -> CompileUnit | None:
    """Parse one recorded javac command line; None if it compiles no Java sources."""
    values: dict[str, str] = {}
    sources: list[Path] = []
    index = 0
    while index < len(args):
        arg = args[index]
        if arg.startswith("--") and "=" in arg and arg.split("=", 1)[0] in _OPTIONS_WITH_VALUE:
            name, value = arg.split("=", 1)
            values[name] = value
        elif arg in _OPTIONS_WITH_VALUE and index + 1 < len(args):
            values[arg] = args[index + 1]
            index += 1
        elif not arg.startswith("-") and arg.endswith(".java"):
            sources.append(_resolve(arg, cwd))
        index += 1

    if not sources:
        return None
    return CompileUnit(
        cwd=cwd,
        sources=tuple(sources),
        classpath=_path_list(_first(values, "-cp", "-classpath", "--class-path"), cwd),
        output_dir=_optional_path(values.get("-d"), cwd),
        generated_source_dir=_optional_path(values.get("-s"), cwd),
        release=_java_level(_first(values, "--release", "-source", "--source")),
        encoding=values.get("-encoding"),
        processor_path=_path_list(_first(values, "-processorpath", "--processor-path"), cwd),
        processors=tuple(p for p in (values.get("-processor") or "").split(",") if p),
        label=label,
    )


def load_compile_units(capture_file: Path) -> tuple[CompileUnit, ...]:
    """Read a JSONL capture file; identical invocations are kept once."""
    units: list[CompileUnit] = []
    seen: set[str] = set()
    if not capture_file.is_file():
        return ()
    for line in capture_file.read_text().splitlines():
        if not line.strip() or line in seen:
            continue
        seen.add(line)
        record = json.loads(line)
        unit = parse_javac_invocation(
            [str(arg) for arg in record["args"]],
            cwd=Path(record["cwd"]),
            label=record.get("task"),
        )
        if unit is not None:
            units.append(unit)
    return tuple(units)


def merge_compile_units(
    units: tuple[CompileUnit, ...],
    *,
    workspace_root: Path,
    before_build: Mapping[Path, int],
) -> AnalysisInputs:
    """Merge units into one analysis universe.

    `before_build` is `snapshot_classpath_candidates(workspace_root)` taken just before the
    capture build. Classpath entries inside the workspace that the build created or rewrote
    are build artifacts (such as a sibling module's jar) and are dropped; their classes come
    from the sources being analyzed. Everything else, like a jar checked into `lib/`, is kept.
    """
    if not units:
        raise UnsupportedProjectError(
            "The build compiled no Java sources, so there is nothing to analyze. Check that the build "
            "command compiles the project's main sources (and cleans first, so javac actually runs)."
        )
    workspace_root = workspace_root.resolve()

    # module-info.java is left out so every unit's sources compile together on the classpath.
    sources = _unique(
        path.resolve() for unit in units for path in unit.sources if path.name != "module-info.java"
    )
    source_set = set(sources)
    generated = _unique(
        path.resolve()
        for unit in units
        if unit.generated_source_dir is not None and unit.generated_source_dir.is_dir()
        for path in sorted(unit.generated_source_dir.rglob("*.java"))
        if path.resolve() not in source_set and path.name != "module-info.java"
    )

    outputs = {unit.output_dir.resolve() for unit in units if unit.output_dir is not None}

    def is_build_artifact(entry: Path) -> bool:
        if entry in outputs:
            return True
        if not _is_within(entry, workspace_root):
            return False
        return before_build.get(entry) != entry.stat().st_mtime_ns

    classpath = _unique(
        entry.resolve()
        for unit in units
        for entry in unit.classpath
        if entry.exists() and not is_build_artifact(entry.resolve())
    )

    for entry in (*classpath, *(path for unit in units for path in unit.processor_path)):
        if any(marker in f"/{entry.as_posix()}" for marker in _LOMBOK_MARKERS) or entry.name.startswith("lombok"):
            raise UnsupportedProjectError(
                f"The build uses Lombok ({entry}), which rewrites code during compilation in a way "
                "Arodnap cannot reproduce from the sources. Lombok projects are not supported yet."
            )

    releases = [unit.release for unit in units if unit.release is not None]
    encodings = [unit.encoding for unit in units if unit.encoding]
    all_sources = (*sources, *generated)
    return AnalysisInputs(
        units=units,
        sources=sources,
        generated_sources=generated,
        classpath=classpath,
        release=max(releases) if releases else None,
        encoding=encodings[0] if encodings else None,
        analysis_root=analysis_root(all_sources),
    )


def snapshot_classpath_candidates(workspace_root: Path, *, skip: str | None = None) -> dict[Path, int]:
    """Modification times of every directory and jar/zip in the workspace, keyed by path."""
    snapshot: dict[Path, int] = {}
    workspace_root = workspace_root.resolve()
    for directory, subdirs, files in os.walk(workspace_root):
        if skip is not None and skip in subdirs:
            subdirs.remove(skip)
        base = Path(directory)
        snapshot[base] = base.stat().st_mtime_ns
        for name in files:
            if name.endswith((".jar", ".zip", ".JAR", ".ZIP")):
                path = base / name
                snapshot[path] = path.stat().st_mtime_ns
    return snapshot


def analysis_root(sources: tuple[Path, ...]) -> Path:
    """Directory RLFixer treats as its project directory.

    The nearest common directory of all sources, moved up while any source's relative path
    would start with `src/`, because RLFixer strips that prefix from its source list.
    """
    root = Path(os.path.commonpath([str(path.parent) for path in sources]))
    while any(path.relative_to(root).parts[:1] == ("src",) for path in sources) and root.parent != root:
        root = root.parent
    return root


def _first(values: dict[str, str], *names: str) -> str | None:
    for name in names:
        if name in values:
            return values[name]
    return None


def _resolve(path_text: str, cwd: Path) -> Path:
    path = Path(path_text)
    return path if path.is_absolute() else cwd / path


def _optional_path(path_text: str | None, cwd: Path) -> Path | None:
    return _resolve(path_text, cwd) if path_text else None


def _path_list(value: str | None, cwd: Path) -> tuple[Path, ...]:
    entries: list[Path] = []
    for part in (value or "").split(os.pathsep):
        if not part or part in {'""', "''"}:
            continue
        if part.endswith(("/*", os.sep + "*")):
            # javac's "dir/*" means every jar in dir.
            directory = _resolve(part[:-2], cwd)
            entries.extend(sorted(directory.glob("*.jar")) + sorted(directory.glob("*.JAR")))
        else:
            entries.append(_resolve(part, cwd))
    return tuple(entries)


def _java_level(value: str | None) -> int | None:
    if not value:
        return None
    text = value.strip()
    if text.startswith("1."):
        text = text[2:]
    return int(text) if text.isdigit() else None


def _unique(paths) -> tuple[Path, ...]:
    seen: dict[Path, None] = {}
    for path in paths:
        seen.setdefault(path, None)
    return tuple(seen)


def _is_within(path: Path, directory: Path) -> bool:
    try:
        path.relative_to(directory)
    except ValueError:
        return False
    return True


__all__ = [
    "AnalysisInputs",
    "CompileUnit",
    "analysis_root",
    "load_compile_units",
    "merge_compile_units",
    "parse_javac_invocation",
    "snapshot_classpath_candidates",
]
