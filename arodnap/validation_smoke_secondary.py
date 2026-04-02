from __future__ import annotations

import argparse
import json
import shutil
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
    ValidationCommandResult,
    build_validation_env,
    render_command_failure,
    run_validation_command,
    tool_repo_root,
)


SECONDARY_REPO_NAME = "gitpod-io/template-java-spring-boot-gradle"
SECONDARY_REPO_SHA = "f09df7aecf75a26e92041307b112c9f7b2b241ca"
SECONDARY_REPO_URL = "https://github.com/gitpod-io/template-java-spring-boot-gradle.git"
SECONDARY_REPO_DIRNAME = "gitpod-template-java-spring-boot-gradle"


@dataclass(frozen=True)
class Step3SmokeResult:
    repo_name: str
    pinned_sha: str
    repo_root: Path
    python_executable: Path
    python_version: str
    java_version: str
    gradle_user_home: Path
    validation_root: Path
    commands: list[ValidationCommandResult]
    artifact_checks: dict[str, bool]
    repair_requested: bool
    success: bool
    failure_reason: str | None
    overall_exit_code: int

    def to_dict(self) -> dict[str, Any]:
        return {
            "repo_name": self.repo_name,
            "pinned_sha": self.pinned_sha,
            "repo_root": str(self.repo_root),
            "python_executable": str(self.python_executable),
            "python_version": self.python_version,
            "java_version": self.java_version,
            "gradle_user_home": str(self.gradle_user_home),
            "validation_root": str(self.validation_root),
            "commands": [command.to_dict() for command in self.commands],
            "artifact_checks": dict(self.artifact_checks),
            "repair_requested": self.repair_requested,
            "success": self.success,
            "failure_reason": self.failure_reason,
            "overall_exit_code": self.overall_exit_code,
        }


def build_parser(defaults: ValidationSessionConfig | None = None) -> argparse.ArgumentParser:
    defaults = defaults or build_validation_session_config()
    parser = argparse.ArgumentParser(prog="python -m arodnap.validation_smoke_secondary")
    add_validation_session_arguments(parser, defaults=defaults)
    parser.add_argument("--include-repair", action="store_true")
    return parser


def run_step3_secondary_smoke(
    config: ValidationSessionConfig,
    *,
    include_repair: bool = False,
    env: dict[str, str] | None = None,
) -> Step3SmokeResult:
    session = setup_validation_session(config)
    run_env = build_validation_env(session, env=env)
    cli_cwd = tool_repo_root()
    repo_root = session.validation_root / SECONDARY_REPO_DIRNAME
    out_analyze = session.validation_root / "out-gitpod-analyze"
    out_infer = session.validation_root / "out-gitpod-infer"
    out_repair = session.validation_root / "out-gitpod-repair"

    _prepare_paths(repo_root, out_analyze, out_infer, out_repair)

    commands: list[ValidationCommandResult] = []
    artifact_checks: dict[str, bool] = {}

    def fail(reason: str, *, exit_code: int = 1) -> Step3SmokeResult:
        return Step3SmokeResult(
            repo_name=SECONDARY_REPO_NAME,
            pinned_sha=SECONDARY_REPO_SHA,
            repo_root=repo_root,
            python_executable=session.python_executable,
            python_version=session.python_version,
            java_version=session.java_version,
            gradle_user_home=session.gradle_user_home,
            validation_root=session.validation_root,
            commands=list(commands),
            artifact_checks=dict(artifact_checks),
            repair_requested=include_repair,
            success=False,
            failure_reason=reason,
            overall_exit_code=exit_code,
        )

    def run(label: str, command: list[str], *, cwd: Path, command_env: dict[str, str] | None = None):
        result = run_validation_command(command, label=label, cwd=cwd, env=command_env)
        commands.append(result)
        return result

    clone = run(
        "clone",
        ["git", "clone", "--depth", "1", SECONDARY_REPO_URL, str(repo_root)],
        cwd=session.validation_root,
    )
    if not clone.success:
        return fail(
            render_command_failure("Step 3 failed while cloning the smoke repo.", clone),
            exit_code=clone.exit_code or 1,
        )

    fetch = run(
        "fetch-pinned-sha",
        ["git", "-C", str(repo_root), "fetch", "--depth", "1", "origin", SECONDARY_REPO_SHA],
        cwd=session.validation_root,
    )
    if not fetch.success:
        return fail(
            render_command_failure("Step 3 failed while fetching the pinned smoke SHA.", fetch),
            exit_code=fetch.exit_code or 1,
        )

    checkout = run(
        "checkout-pinned-sha",
        ["git", "-C", str(repo_root), "checkout", "--detach", SECONDARY_REPO_SHA],
        cwd=session.validation_root,
    )
    if not checkout.success:
        return fail(
            render_command_failure("Step 3 failed while checking out the pinned smoke SHA.", checkout),
            exit_code=checkout.exit_code or 1,
        )

    sanity = run(
        "gradle-sanity-build",
        [str(repo_root / "gradlew"), "-p", str(repo_root), "classes"],
        cwd=session.validation_root,
        command_env=run_env,
    )
    if not sanity.success:
        return fail(
            render_command_failure("Step 3 failed during the Gradle sanity build.", sanity),
            exit_code=sanity.exit_code or 1,
        )

    analyze = run(
        "analyze",
        [
            str(session.python_executable),
            "-m",
            "arodnap.main",
            "analyze",
            "--out-dir",
            str(out_analyze),
            str(repo_root),
        ],
        cwd=cli_cwd,
        command_env=run_env,
    )
    if not analyze.success:
        return fail(
            render_command_failure("Step 3 failed during `arodnap analyze`.", analyze),
            exit_code=analyze.exit_code or 1,
        )

    infer = run(
        "infer",
        [
            str(session.python_executable),
            "-m",
            "arodnap.main",
            "infer",
            "--out-dir",
            str(out_infer),
            str(repo_root),
        ],
        cwd=cli_cwd,
        command_env=run_env,
    )
    if not infer.success:
        return fail(
            render_command_failure("Step 3 failed during `arodnap infer`.", infer),
            exit_code=infer.exit_code or 1,
        )

    if include_repair:
        repair = run(
            "repair",
            [
                str(session.python_executable),
                "-m",
                "arodnap.main",
                "repair",
                "--out-dir",
                str(out_repair),
                str(repo_root),
            ],
            cwd=cli_cwd,
            command_env=run_env,
        )
        if not repair.success:
            return fail(
                render_command_failure("Step 3 failed during optional `arodnap repair`.", repair),
                exit_code=repair.exit_code or 1,
            )

    artifact_checks = {
        "out-gitpod-analyze/manifest.json": (out_analyze / "manifest.json").is_file(),
        "out-gitpod-analyze/report.json": (out_analyze / "report.json").is_file(),
        "out-gitpod-analyze/diagnostics/": (out_analyze / "diagnostics").is_dir(),
        "out-gitpod-infer/manifest.json": (out_infer / "manifest.json").is_file(),
        "out-gitpod-infer/report.json": (out_infer / "report.json").is_file(),
        "out-gitpod-infer/diagnostics/": (out_infer / "diagnostics").is_dir(),
        "out-gitpod-infer/inference/initial": (out_infer / "inference" / "initial").is_dir(),
    }
    if include_repair:
        artifact_checks.update(
            {
                "out-gitpod-repair/stages/": (out_repair / "stages").is_dir(),
                "out-gitpod-repair/patches/manifest.json": (out_repair / "patches" / "manifest.json").is_file(),
            }
        )

    missing_artifacts = [name for name, exists in artifact_checks.items() if not exists]
    if missing_artifacts:
        return fail("Step 3 is missing required artifacts:\n" + "\n".join(missing_artifacts))

    return Step3SmokeResult(
        repo_name=SECONDARY_REPO_NAME,
        pinned_sha=SECONDARY_REPO_SHA,
        repo_root=repo_root,
        python_executable=session.python_executable,
        python_version=session.python_version,
        java_version=session.java_version,
        gradle_user_home=session.gradle_user_home,
        validation_root=session.validation_root,
        commands=list(commands),
        artifact_checks=dict(artifact_checks),
        repair_requested=include_repair,
        success=True,
        failure_reason=None,
        overall_exit_code=0,
    )


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    config = validation_session_config_from_args(args)

    try:
        result = run_step3_secondary_smoke(
            config,
            include_repair=args.include_repair,
        )
    except ValidationSessionError as exc:
        print(str(exc), file=sys.stderr)
        return 1

    print(json.dumps(result.to_dict(), indent=2, sort_keys=True))
    if not result.success and result.failure_reason is not None:
        print(result.failure_reason, file=sys.stderr)
    return result.overall_exit_code


def _prepare_paths(repo_root: Path, out_analyze: Path, out_infer: Path, out_repair: Path) -> None:
    for path in (repo_root, out_analyze, out_infer, out_repair):
        shutil.rmtree(path, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
