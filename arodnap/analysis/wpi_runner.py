from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import os
import re
import shutil
import subprocess

from arodnap.contracts import RunConfig


@dataclass(frozen=True)
class WpiRunResult:
    log_path: Path
    inference_dir: Path


class WpiRunError(RuntimeError):
    pass


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
    env = os.environ.copy()
    env["CHECKERFRAMEWORK"] = str(config.cf_root)

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
