from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


ARODNAP_PYTHON_ENV = "ARODNAP_PYTHON"
GRADLE_USER_HOME_ENV = "GRADLE_USER_HOME"
ARODNAP_VALIDATION_ROOT_ENV = "ARODNAP_VALIDATION_ROOT"

DEFAULT_GRADLE_USER_HOME = Path("/tmp/arodnap-gradle-home")
DEFAULT_VALIDATION_ROOT = Path("/tmp/arodnap-validation")

_PYTHON_VERSION_PATTERN = re.compile(r"Python\s+(\d+)\.(\d+)(?:\.(\d+))?")


class ValidationSessionError(RuntimeError):
    pass


@dataclass(frozen=True)
class ValidationSessionConfig:
    python_executable: Path
    gradle_user_home: Path
    validation_root: Path


@dataclass(frozen=True)
class ValidationSession:
    python_executable: Path
    python_version: str
    java_version: str
    gradle_user_home: Path
    validation_root: Path

    def to_dict(self) -> dict[str, Any]:
        return {
            "python_executable": str(self.python_executable),
            "python_version": self.python_version,
            "java_version": self.java_version,
            "gradle_user_home": str(self.gradle_user_home),
            "validation_root": str(self.validation_root),
        }


def build_validation_session_config(env: dict[str, str] | None = None) -> ValidationSessionConfig:
    if env is None:
        env = os.environ
    python_value = env.get(ARODNAP_PYTHON_ENV) or sys.executable
    gradle_home_value = env.get(GRADLE_USER_HOME_ENV) or str(DEFAULT_GRADLE_USER_HOME)
    validation_root_value = env.get(ARODNAP_VALIDATION_ROOT_ENV) or str(DEFAULT_VALIDATION_ROOT)
    return ValidationSessionConfig(
        python_executable=Path(python_value).expanduser().resolve(),
        gradle_user_home=Path(gradle_home_value).expanduser().resolve(),
        validation_root=Path(validation_root_value).expanduser().resolve(),
    )


def setup_validation_session(config: ValidationSessionConfig) -> ValidationSession:
    _ensure_directory(config.gradle_user_home, env_var=GRADLE_USER_HOME_ENV)
    _ensure_directory(config.validation_root, env_var=ARODNAP_VALIDATION_ROOT_ENV)

    python_version = _run_version_command([str(config.python_executable), "--version"], label="Python")
    _validate_python_version(python_version, config.python_executable)
    java_version = _run_version_command(["java", "-version"], label="Java")

    return ValidationSession(
        python_executable=config.python_executable,
        python_version=python_version,
        java_version=java_version,
        gradle_user_home=config.gradle_user_home,
        validation_root=config.validation_root,
    )


def build_parser(defaults: ValidationSessionConfig | None = None) -> argparse.ArgumentParser:
    defaults = defaults or build_validation_session_config()
    parser = argparse.ArgumentParser(prog="python -m arodnap.validation_session")
    add_validation_session_arguments(parser, defaults=defaults)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    config = validation_session_config_from_args(args)

    try:
        session = setup_validation_session(config)
    except ValidationSessionError as exc:
        print(str(exc), file=sys.stderr)
        return 1

    print(json.dumps(session.to_dict(), indent=2, sort_keys=True))
    return 0


def add_validation_session_arguments(
    parser: argparse.ArgumentParser,
    *,
    defaults: ValidationSessionConfig | None = None,
) -> None:
    defaults = defaults or build_validation_session_config()
    parser.add_argument("--python", default=str(defaults.python_executable))
    parser.add_argument("--gradle-user-home", default=str(defaults.gradle_user_home))
    parser.add_argument("--validation-root", default=str(defaults.validation_root))


def validation_session_config_from_args(args: argparse.Namespace) -> ValidationSessionConfig:
    return ValidationSessionConfig(
        python_executable=Path(args.python).expanduser().resolve(),
        gradle_user_home=Path(args.gradle_user_home).expanduser().resolve(),
        validation_root=Path(args.validation_root).expanduser().resolve(),
    )


def _ensure_directory(path: Path, *, env_var: str) -> None:
    try:
        path.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        raise ValidationSessionError(f"Failed to create {env_var} at {path}: {exc}") from exc
    if not path.is_dir():
        raise ValidationSessionError(f"{env_var} is not a directory: {path}")


def _run_version_command(command: list[str], *, label: str) -> str:
    try:
        completed = subprocess.run(command, capture_output=True, text=True, check=False)
    except FileNotFoundError as exc:
        raise ValidationSessionError(f"{label} is required but was not found: {command[0]}") from exc

    if completed.returncode != 0:
        output = _normalize_version_output(completed)
        raise ValidationSessionError(
            f"{label} version check failed with exit code {completed.returncode}: {output}"
        )

    output = _normalize_version_output(completed)
    if not output:
        raise ValidationSessionError(f"{label} version check returned no output")
    return output


def _normalize_version_output(completed: subprocess.CompletedProcess[str]) -> str:
    stdout = completed.stdout.strip()
    stderr = completed.stderr.strip()
    if stdout and stderr:
        return f"{stdout}\n{stderr}"
    return stdout or stderr


def _validate_python_version(version_output: str, python_executable: Path) -> None:
    match = _PYTHON_VERSION_PATTERN.search(version_output)
    if match is None:
        raise ValidationSessionError(f"Unable to parse Python version from: {version_output}")

    major = int(match.group(1))
    minor = int(match.group(2))
    if (major, minor) < (3, 10):
        raise ValidationSessionError(
            f"Python 3.10+ is required for validation, got {major}.{minor} from {python_executable}"
        )


if __name__ == "__main__":
    raise SystemExit(main())
