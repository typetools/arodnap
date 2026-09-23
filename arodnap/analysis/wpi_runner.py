from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
import os
import re
import shutil
import sys
import tempfile

from arodnap.contracts import RunConfig
from arodnap.runtime import (
    CommandExecutionError,
    JdkResolutionError,
    render_command_log,
    resolve_jdk,
    run_command,
)


@dataclass(frozen=True)
class WpiRunResult:
    log_path: Path
    inference_dir: Path


class WpiRunError(RuntimeError):
    pass


DLJC_PYTHON_ENV = "ARODNAP_WPI_PYTHON"
_WPI_JAVA_VERSION_CHECK = re.compile(r'"\$\{java_version\}" = (\d+) \]')
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
        try:
            command_result = run_command(command, cwd=workspace_root, env=env)
        except CommandExecutionError as exc:
            raise WpiRunError(str(exc)) from exc

    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_path.write_text(
        render_command_log(
            command_result,
            tool_name="wpi",
            timeout_seconds=config.timeouts.analysis_seconds,
        )
    )

    if command_result.returncode != 0:
        raise WpiRunError(
            f"WPI failed for {workspace_root}. See log: {log_path}"
        )

    generated_inference_dir = _locate_generated_inference_dir(
        workspace_root=workspace_root,
        stdout=command_result.stdout,
    )
    if generated_inference_dir is None:
        # wpi.sh exits 0 even when dljc could not build the project; surface its reason.
        reasons = [
            line.strip()
            for line in command_result.stdout.splitlines()
            if line.startswith("wpi.sh:")
        ]
        detail = f" {reasons[-1]}" if reasons else ""
        raise WpiRunError(
            f"WPI produced no inferred annotations.{detail} See log: {log_path}"
        )

    if inference_root.exists():
        shutil.rmtree(inference_root)
    shutil.copytree(generated_inference_dir, inference_root)

    return WpiRunResult(
        log_path=log_path,
        inference_dir=inference_root,
    )


def _build_wpi_command(config: RunConfig, workspace_root: Path) -> list[str]:
    # wpi.sh defaults Gradle's user home to <project>/.gradle, which re-downloads every
    # dependency on each run and leaves daemons writing into the deleted workspace.
    build_args = ["--no-daemon", *config.build_args]
    command = [
        "bash",
        str((config.cf_root / "checker" / "bin" / "wpi.sh").resolve()),
        "-d",
        str(workspace_root),
        "-g",
        str(_gradle_user_home()),
        "-b",
        " ".join(build_args),
    ]
    if config.compile_target:
        command.extend(["-c", config.compile_target])
    command.extend(["--", "--checker", "resourceleak"])
    return command


def _gradle_user_home() -> Path:
    configured = os.environ.get("GRADLE_USER_HOME")
    return Path(configured).expanduser() if configured else Path.home() / ".gradle"


@contextmanager
def _wpi_environment(cf_root: Path):
    env = os.environ.copy()
    env["CHECKERFRAMEWORK"] = str(cf_root)
    env["JAVA_HOME"] = str(_resolve_wpi_java_home(env, cf_root))

    compatible_python = resolve_dljc_python()
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


def wpi_supported_jdk_majors(cf_root: Path) -> tuple[int, ...]:
    """JDK major versions the given Checker Framework's wpi.sh accepts as JAVA_HOME.

    Read from the script itself because the list changes between releases
    (3.49.0 accepts 8, 11, 17, 20, 21; 4.2.3 accepts 8, 11, 17, 21, 24, 25, 26).
    """
    wpi_script = cf_root / "checker" / "bin" / "wpi.sh"
    try:
        text = wpi_script.read_text()
    except OSError:
        return ()
    return tuple(sorted({int(major) for major in _WPI_JAVA_VERSION_CHECK.findall(text)}))


def _resolve_wpi_java_home(env: dict[str, str], cf_root: Path) -> Path:
    try:
        jdk = resolve_jdk(env)
    except JdkResolutionError as exc:
        raise WpiRunError(str(exc)) from exc
    supported_majors = wpi_supported_jdk_majors(cf_root)
    if jdk.major_version not in supported_majors:
        supported = ", ".join(str(major) for major in supported_majors)
        raise WpiRunError(
            f"Whole-program inference with {cf_root.name} needs a JDK {supported}; found JDK {jdk.major_version} "
            f"at {jdk.home} (from {jdk.source}). Set JAVA_HOME to a supported JDK."
        )
    return jdk.home


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
    stdout: str,
) -> Path | None:
    match = re.search(
        r"^Directory for generated annotation files:\s*(?P<path>.+?)\s*$",
        stdout,
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


def resolve_dljc_python() -> Path | None:
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
        command_result = run_command([str(python_executable), "-c", "import distutils"])
    except CommandExecutionError:
        return False
    return command_result.returncode == 0
