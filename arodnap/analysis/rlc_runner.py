from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path
import re
import tempfile

from arodnap.contracts import RunConfig
from arodnap.runtime import (
    CommandExecutionError,
    CommandTimeoutError,
    javac_language_options,
    render_command_log,
    run_command,
)

from .checker_framework import (
    RESOURCE_LEAK_CHECKER,
    CheckerFrameworkError,
    checker_javac_command,
    resolve_analysis_jdk,
)

_WARNING_PATTERN = re.compile(r"(?m)^.*: warning:")
# Same flags as the paper's final Resource Leak Checker pass (legacy RLCRunner.py),
# minus JVM heap and assertion settings.
_RLC_FLAGS = [
    "-Adetailedmsgtext",
    "-Awarns",
    "-Xmaxwarns",
    "10000",
    "-ApermitStaticOwning",
    "-AshowPrefixInWarningMessages",
    "-AenableReturnsReceiverForRlc",
]
# Arodnap's stub files for the Resource Leak Checker (e.g. side-effect-free close()).
RLC_STUBS_DIR = Path(__file__).resolve().parents[2] / "checker_framework" / "stubs"


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
    inference_dir: Path | None,
    diagnostics_path: Path,
    release: int | None = None,
    encoding: str | None = None,
) -> RlcRunResult:
    workspace_root = workspace_root.resolve()
    source_files_file = _require_file(source_files_file, "source files file")
    classpath_entries_file = _require_file(classpath_entries_file, "classpath entries file")
    diagnostics_path = diagnostics_path.resolve()
    if inference_dir is not None:
        inference_dir = _require_directory(inference_dir, "inference directory")

    classpath_entries = [line.strip() for line in classpath_entries_file.read_text().splitlines() if line.strip()]
    if not classpath_entries:
        raise RlcRunError(f"Classpath entries file is empty: {classpath_entries_file}")

    try:
        checker_command = checker_javac_command(config.cf_root, resolve_analysis_jdk(config.cf_root))
    except CheckerFrameworkError as exc:
        raise RlcRunError(str(exc)) from exc

    diagnostics_path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="arodnap-rlc-classes-") as classes_dir:
        command = [
            *checker_command,
            "-processor",
            RESOURCE_LEAK_CHECKER,
            *_RLC_FLAGS,
            f"-Astubs={RLC_STUBS_DIR}",
        ]
        if inference_dir is not None:
            command.append(f"-Aajava={inference_dir}")
        command.extend(javac_language_options(release, encoding))
        command.extend(
            [
                "-classpath",
                os.pathsep.join(classpath_entries),
                "-d",
                classes_dir,
                f"@{source_files_file}",
            ]
        )
        try:
            command_result = run_command(
                command, cwd=workspace_root, timeout_seconds=config.timeouts.analysis_seconds
            )
        except CommandTimeoutError as exc:
            raise RlcRunError(f"The Resource Leak Checker exceeded --analysis-timeout. {exc}") from exc
        except CommandExecutionError as exc:
            raise RlcRunError(str(exc)) from exc

    diagnostics_text = render_command_log(
        command_result,
        tool_name="rlc",
        timeout_seconds=config.timeouts.analysis_seconds,
    )
    diagnostics_path.write_text(diagnostics_text)

    if command_result.returncode != 0:
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
