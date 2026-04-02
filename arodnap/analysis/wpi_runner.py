from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
import os
import re
import shutil
import subprocess
import sys
import tempfile

from arodnap.contracts import RunConfig


@dataclass(frozen=True)
class WpiRunResult:
    log_path: Path
    inference_dir: Path


class WpiRunError(RuntimeError):
    pass


DLJC_PYTHON_ENV = "ARODNAP_WPI_PYTHON"
_PYTHON_CANDIDATE_NAMES = (
    "python3",
    "python3.12",
    "python3.11",
    "python3.10",
    "python3.9",
)


def run_wpi(
    config: RunConfig,
    *,
    workspace_root: Path,
    log_path: Path,
    inference_root: Path,
) -> WpiRunResult:
    workspace_root = workspace_root.resolve()
    log_path = log_path.resolve()
    inference_root = inference_root.resolve()

    _prepare_wpi_support_files(config.cf_root)
    command = _build_wpi_command(config, workspace_root)
    with _wpi_environment(config.cf_root) as env:
        completed = subprocess.run(
            command,
            cwd=workspace_root,
            env=env,
            capture_output=True,
            text=True,
            check=False,
        )

    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_path.write_text(_render_log(command, completed))

    if completed.returncode != 0:
        raise WpiRunError(
            f"WPI failed for {workspace_root}. See log: {log_path}"
        )

    generated_inference_dir = _locate_generated_inference_dir(
        workspace_root=workspace_root,
        completed=completed,
    )
    if generated_inference_dir is None:
        raise WpiRunError(
            "WPI succeeded but no inferred output directory could be located."
        )

    if inference_root.exists():
        shutil.rmtree(inference_root)
    shutil.copytree(generated_inference_dir, inference_root)

    return WpiRunResult(
        log_path=log_path,
        inference_dir=inference_root,
    )


def _build_wpi_command(config: RunConfig, workspace_root: Path) -> list[str]:
    command = [
        "bash",
        str((config.cf_root / "checker" / "bin" / "wpi.sh").resolve()),
        "-d",
        str(workspace_root),
    ]
    if config.build_args:
        command.extend(["-b", " ".join(config.build_args)])
    if config.compile_target:
        command.extend(["-c", config.compile_target])
    command.extend(["--", "--checker", "resourceleak"])
    return command


@contextmanager
def _wpi_environment(cf_root: Path):
    env = os.environ.copy()
    env["CHECKERFRAMEWORK"] = str(cf_root)

    compatible_python = _resolve_dljc_python3()
    if compatible_python is None:
        raise WpiRunError(
            "Whole-program inference requires a python3 interpreter with distutils for Checker Framework dljc."
        )

    with tempfile.TemporaryDirectory(prefix="arodnap-wpi-python-") as shim_dir:
        shim_path = Path(shim_dir) / "python3"
        shim_path.symlink_to(compatible_python)
        current_path = env.get("PATH", "")
        env["PATH"] = (
            f"{shim_dir}{os.pathsep}{current_path}"
            if current_path
            else shim_dir
        )
        yield env


def _prepare_wpi_support_files(cf_root: Path) -> None:
    _ensure_executable(cf_root / "checker" / "bin" / "wpi.sh")
    _ensure_executable(cf_root / "checker" / "bin" / ".do-like-javac" / "dljc")


def _ensure_executable(path: Path) -> None:
    path = path.resolve()
    if not path.is_file():
        raise WpiRunError(f"Missing required WPI support file: {path}")
    current_mode = path.stat().st_mode
    if current_mode & 0o111:
        return
    path.chmod(current_mode | 0o111)


def _locate_generated_inference_dir(
    *,
    workspace_root: Path,
    completed: subprocess.CompletedProcess[str],
) -> Path | None:
    match = re.search(
        r"^Directory for generated annotation files:\s*(?P<path>.+?)\s*$",
        completed.stdout,
        flags=re.MULTILINE,
    )
    if match:
        candidate = Path(match.group("path")).expanduser().resolve()
        if candidate.is_dir():
            return candidate

    legacy_candidate = workspace_root / "build" / "whole-program-inference"
    if legacy_candidate.is_dir():
        return legacy_candidate

    return None


def _resolve_dljc_python3() -> Path | None:
    candidates = []
    override = os.environ.get(DLJC_PYTHON_ENV)
    if override:
        candidates.append(override)
    candidates.append(sys.executable)
    candidates.extend(_PYTHON_CANDIDATE_NAMES)
    candidates.append("/usr/bin/python3")

    seen: set[Path] = set()
    for candidate in candidates:
        resolved = _resolve_python_candidate(candidate)
        if resolved is None or resolved in seen:
            continue
        seen.add(resolved)
        if _python_supports_distutils(resolved):
            return resolved
    return None


def _resolve_python_candidate(candidate: str) -> Path | None:
    if not candidate:
        return None
    candidate_path = Path(candidate).expanduser()
    if candidate_path.is_absolute():
        return candidate_path.resolve() if candidate_path.is_file() else None
    resolved = shutil.which(candidate)
    return Path(resolved).resolve() if resolved else None


def _python_supports_distutils(python_executable: Path) -> bool:
    try:
        completed = subprocess.run(
            [str(python_executable), "-c", "import distutils"],
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError:
        return False
    return completed.returncode == 0


def _render_log(command: list[str], completed: subprocess.CompletedProcess[str]) -> str:
    sections = [
        f"COMMAND: {' '.join(command)}",
        f"EXIT_CODE: {completed.returncode}",
        "STDOUT:",
        completed.stdout,
        "STDERR:",
        completed.stderr,
    ]
    return "\n".join(sections)
