from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import os
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

    generated_inference_dir = workspace_root / "build" / "whole-program-inference"
    if not generated_inference_dir.is_dir():
        raise WpiRunError(
            f"WPI succeeded but no inferred output was found at {generated_inference_dir}."
        )

    if inference_root.exists():
        shutil.rmtree(inference_root)
    shutil.copytree(generated_inference_dir, inference_root)

    return WpiRunResult(
        log_path=log_path,
        inference_dir=inference_root,
    )


def _build_wpi_command(config: RunConfig, workspace_root: Path) -> list[str]:
    command = [str(config.cf_root / "checker" / "bin" / "wpi.sh"), "-d", str(workspace_root)]
    if config.build_args:
        command.extend(["-b", " ".join(config.build_args)])
    if config.compile_target:
        command.extend(["-c", config.compile_target])
    return command


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
