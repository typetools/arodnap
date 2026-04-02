from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from arodnap.validation_session import (
    ValidationSessionError,
    ValidationSessionConfig,
    add_validation_session_arguments,
    build_validation_session_config,
    setup_validation_session,
    validation_session_config_from_args,
)
from arodnap.validation_support import (
    build_validation_env,
    render_command_failure,
    run_validation_command,
    tool_repo_root,
)


STEP1_TEST_MODULES = (
    "tests.test_v1_integration",
    "tests.test_gradle_adapter",
    "tests.test_analysis_commands",
    "tests.test_analyze_once",
    "tests.test_reanalyze",
    "tests.test_rlc_runner",
    "tests.test_wpi_runner",
)


class RegressionRunnerError(RuntimeError):
    pass


@dataclass(frozen=True)
class RegressionRunResult:
    python_executable: Path
    python_version: str
    java_version: str
    gradle_user_home: Path
    validation_root: Path
    cwd: Path
    command: list[str]
    test_modules: list[str]
    exit_code: int
    success: bool
    stdout: str
    stderr: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "python_executable": str(self.python_executable),
            "python_version": self.python_version,
            "java_version": self.java_version,
            "gradle_user_home": str(self.gradle_user_home),
            "validation_root": str(self.validation_root),
            "cwd": str(self.cwd),
            "command": list(self.command),
            "test_modules": list(self.test_modules),
            "exit_code": self.exit_code,
            "success": self.success,
        }


def build_parser(defaults: ValidationSessionConfig | None = None) -> argparse.ArgumentParser:
    defaults = defaults or build_validation_session_config()
    parser = argparse.ArgumentParser(prog="python -m arodnap.validation_regression")
    add_validation_session_arguments(parser, defaults=defaults)
    return parser


def run_step1_regression(
    config: ValidationSessionConfig,
    *,
    env: dict[str, str] | None = None,
    cwd: Path | None = None,
) -> RegressionRunResult:
    session = setup_validation_session(config)
    run_cwd = (cwd or tool_repo_root()).resolve()
    command = [str(session.python_executable), "-m", "unittest", *STEP1_TEST_MODULES]
    completed = run_validation_command(
        command,
        label="step1-regression",
        cwd=run_cwd,
        env=build_validation_env(session, env=env),
    )
    if completed.invocation_error is not None:
        raise RegressionRunnerError(f"Failed to invoke Step 1 regression command: {completed.invocation_error}")

    return RegressionRunResult(
        python_executable=session.python_executable,
        python_version=session.python_version,
        java_version=session.java_version,
        gradle_user_home=session.gradle_user_home,
        validation_root=session.validation_root,
        cwd=run_cwd,
        command=command,
        test_modules=list(STEP1_TEST_MODULES),
        exit_code=completed.exit_code or 0,
        success=completed.success,
        stdout=completed.stdout,
        stderr=completed.stderr,
    )


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    config = validation_session_config_from_args(args)

    try:
        result = run_step1_regression(config)
    except (ValidationSessionError, RegressionRunnerError) as exc:
        print(str(exc), file=sys.stderr)
        return 1

    print(json.dumps(result.to_dict(), indent=2, sort_keys=True))
    if not result.success:
        print(
            render_command_failure(
                f"Step 1 regression failed with exit code {result.exit_code}.",
                result=_as_command_result(result),
            ),
            file=sys.stderr,
        )
    return result.exit_code


def _as_command_result(result: RegressionRunResult):
    from arodnap.validation_support import ValidationCommandResult

    return ValidationCommandResult(
        label="step1-regression",
        command=result.command,
        cwd=result.cwd,
        exit_code=result.exit_code,
        success=result.success,
        stdout=result.stdout,
        stderr=result.stderr,
        invocation_error=None,
    )


if __name__ == "__main__":
    raise SystemExit(main())
