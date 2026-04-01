from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path
import re
import subprocess
import tempfile

from arodnap.contracts import RunConfig

_WARNING_PATTERN = re.compile(r"(?m)^.*: warning:")
_RLC_FLAGS = [
    "-Adetailedmsgtext",
    "-Awarns",
    "-ApermitStaticOwning",
    "-AshowPrefixInWarningMessages",
    "-AenableReturnsReceiverForRlc",
]


@dataclass(frozen=True)
class RlcRunResult:
    diagnostics_path: Path
    warning_count: int


class RlcRunError(RuntimeError):
    pass


def run_resource_leak_checker(
    config: RunConfig,
    *,
    workspace_root: Path,
    source_files_file: Path,
    classpath_entries_file: Path,
    inference_dir: Path,
    diagnostics_path: Path,
) -> RlcRunResult:
    workspace_root = workspace_root.resolve()
    source_files_file = _require_file(source_files_file, "source files file")
    classpath_entries_file = _require_file(classpath_entries_file, "classpath entries file")
    inference_dir = _require_directory(inference_dir, "inference directory")
    diagnostics_path = diagnostics_path.resolve()

    classpath_entries = [line.strip() for line in classpath_entries_file.read_text().splitlines() if line.strip()]
    if not classpath_entries:
        raise RlcRunError(f"Classpath entries file is empty: {classpath_entries_file}")

    diagnostics_path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="arodnap-rlc-classes-") as classes_dir:
        command = [
            str((config.cf_root / "checker" / "bin" / "javac").resolve()),
            "-processor",
            "org.checkerframework.checker.resourceleak.ResourceLeakChecker",
            *_RLC_FLAGS,
            f"-Aajava={inference_dir}",
            "-classpath",
            os.pathsep.join(classpath_entries),
            "-d",
            classes_dir,
            f"@{source_files_file}",
        ]
        completed = subprocess.run(
            command,
            cwd=workspace_root,
            capture_output=True,
            text=True,
            check=False,
        )

    diagnostics_text = _render_diagnostics(command, completed)
    diagnostics_path.write_text(diagnostics_text)

    if completed.returncode != 0:
        raise RlcRunError(f"RLC failed for {workspace_root}. See diagnostics: {diagnostics_path}")

    return RlcRunResult(
        diagnostics_path=diagnostics_path,
        warning_count=count_warnings(diagnostics_text),
    )


def count_warnings(diagnostics_text: str) -> int:
    return len(_WARNING_PATTERN.findall(diagnostics_text))


def _require_file(path: Path, label: str) -> Path:
    resolved = path.resolve()
    if not resolved.is_file():
        raise RlcRunError(f"Missing {label}: {resolved}")
    return resolved


def _require_directory(path: Path, label: str) -> Path:
    resolved = path.resolve()
    if not resolved.is_dir():
        raise RlcRunError(f"Missing {label}: {resolved}")
    return resolved


def _render_diagnostics(command: list[str], completed: subprocess.CompletedProcess[str]) -> str:
    sections = [
        f"COMMAND: {' '.join(command)}",
        f"EXIT_CODE: {completed.returncode}",
        "STDOUT:",
        completed.stdout,
        "STDERR:",
        completed.stderr,
    ]
    return "\n".join(sections)
