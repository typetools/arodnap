from __future__ import annotations

import argparse
import json
import shutil
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from arodnap.validation_session import (
    ValidationSessionConfig,
    ValidationSessionError,
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


NEGATIVE_REPO_NAME = "spring-guides/gs-spring-boot"
NEGATIVE_REPO_SHA = "f6d6868174a711e89b477a4d19fb1c6e024aa983"
NEGATIVE_REPO_URL = "https://github.com/spring-guides/gs-spring-boot.git"
NEGATIVE_REPO_DIRNAME = "gs-spring-boot"
EXPECTED_REPO_ROOT_FAILURE_FRAGMENTS = (
    "Gradle repo root must contain one of",
    "Gradle repo must use src/main/java in v1.",
)


@dataclass(frozen=True)
class Step4RepoRootNegativeResult:
    repo_name: str
    pinned_sha: str
    repo_root: Path
    python_executable: Path
    python_version: str
    java_version: str
    gradle_user_home: Path
    validation_root: Path
    commands: list[ValidationCommandResult]
    analyze_failed: bool
    repo_status: str | None
    repo_clean: bool | None
    contract_checks: dict[str, bool]
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
            "analyze_failed": self.analyze_failed,
            "repo_status": self.repo_status,
            "repo_clean": self.repo_clean,
            "contract_checks": dict(self.contract_checks),
            "success": self.success,
            "failure_reason": self.failure_reason,
            "overall_exit_code": self.overall_exit_code,
        }


def build_parser(defaults: ValidationSessionConfig | None = None) -> argparse.ArgumentParser:
    defaults = defaults or build_validation_session_config()
    parser = argparse.ArgumentParser(prog="python -m arodnap.validation_negative_repo_root")
    add_validation_session_arguments(parser, defaults=defaults)
    return parser


def run_step4_repo_root_negative(
    config: ValidationSessionConfig,
    *,
    env: dict[str, str] | None = None,
) -> Step4RepoRootNegativeResult:
    session = setup_validation_session(config)
    run_env = build_validation_env(session, env=env)
    cli_cwd = tool_repo_root()
    repo_root = session.validation_root / NEGATIVE_REPO_DIRNAME
    out_analyze = session.validation_root / "out-gs-spring-boot-analyze"

    _prepare_paths(repo_root, out_analyze)

    commands: list[ValidationCommandResult] = []
    analyze_failed = False
    repo_status: str | None = None
    repo_clean: bool | None = None
    contract_checks: dict[str, bool] = {}

    def fail(reason: str, *, exit_code: int = 1) -> Step4RepoRootNegativeResult:
        return Step4RepoRootNegativeResult(
            repo_name=NEGATIVE_REPO_NAME,
            pinned_sha=NEGATIVE_REPO_SHA,
            repo_root=repo_root,
            python_executable=session.python_executable,
            python_version=session.python_version,
            java_version=session.java_version,
            gradle_user_home=session.gradle_user_home,
            validation_root=session.validation_root,
            commands=list(commands),
            analyze_failed=analyze_failed,
            repo_status=repo_status,
            repo_clean=repo_clean,
            contract_checks=dict(contract_checks),
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
        ["git", "clone", "--depth", "1", NEGATIVE_REPO_URL, str(repo_root)],
        cwd=session.validation_root,
    )
    if not clone.success:
        return fail(
            render_command_failure("Step 4 failed while cloning the negative repo.", clone),
            exit_code=clone.exit_code or 1,
        )

    fetch = run(
        "fetch-pinned-sha",
        ["git", "-C", str(repo_root), "fetch", "--depth", "1", "origin", NEGATIVE_REPO_SHA],
        cwd=session.validation_root,
    )
    if not fetch.success:
        return fail(
            render_command_failure("Step 4 failed while fetching the pinned negative SHA.", fetch),
            exit_code=fetch.exit_code or 1,
        )

    checkout = run(
        "checkout-pinned-sha",
        ["git", "-C", str(repo_root), "checkout", "--detach", NEGATIVE_REPO_SHA],
        cwd=session.validation_root,
    )
    if not checkout.success:
        return fail(
            render_command_failure("Step 4 failed while checking out the pinned negative SHA.", checkout),
            exit_code=checkout.exit_code or 1,
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
    analyze_failed = not analyze.success

    status_result = run(
        "repo-status",
        ["git", "-C", str(repo_root), "status", "--short"],
        cwd=session.validation_root,
    )
    if not status_result.success:
        return fail(
            render_command_failure("Step 4 failed while checking repo status after analyze.", status_result),
            exit_code=status_result.exit_code or 1,
        )
    repo_status = status_result.stdout.strip()
    repo_clean = repo_status == ""

    if not analyze_failed:
        return fail(
            "Step 4 expected `arodnap analyze` to reject the unsupported repo-root shape, "
            "but it exited successfully."
        )

    if not repo_clean:
        return fail(
            "Step 4 detected unexpected repo mutations after the negative analyze run.\n"
            f"git status --short output:\n{repo_status}"
        )

    contract_checks = _evaluate_contract_checks(analyze, out_analyze)
    failed_checks = [name for name, passed in contract_checks.items() if not passed]
    if failed_checks:
        return fail(
            "Step 4 analyze failed, but not with the expected explicit early repo-root contract rejection.\n"
            "Failed checks:\n"
            + "\n".join(failed_checks)
            + "\n"
            + render_command_failure("Analyze command output:", analyze)
        )

    return Step4RepoRootNegativeResult(
        repo_name=NEGATIVE_REPO_NAME,
        pinned_sha=NEGATIVE_REPO_SHA,
        repo_root=repo_root,
        python_executable=session.python_executable,
        python_version=session.python_version,
        java_version=session.java_version,
        gradle_user_home=session.gradle_user_home,
        validation_root=session.validation_root,
        commands=list(commands),
        analyze_failed=analyze_failed,
        repo_status=repo_status,
        repo_clean=repo_clean,
        contract_checks=dict(contract_checks),
        success=True,
        failure_reason=None,
        overall_exit_code=0,
    )


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    config = validation_session_config_from_args(args)

    try:
        result = run_step4_repo_root_negative(config)
    except ValidationSessionError as exc:
        print(str(exc), file=sys.stderr)
        return 1

    print(json.dumps(result.to_dict(), indent=2, sort_keys=True))
    if not result.success and result.failure_reason is not None:
        print(result.failure_reason, file=sys.stderr)
    return result.overall_exit_code


def _prepare_paths(repo_root: Path, out_analyze: Path) -> None:
    for path in (repo_root, out_analyze):
        shutil.rmtree(path, ignore_errors=True)


def _evaluate_contract_checks(analyze: ValidationCommandResult, out_analyze: Path) -> dict[str, bool]:
    manifest_payload = _load_json_file(out_analyze / "manifest.json")
    report_payload = _load_json_file(out_analyze / "report.json")
    combined_output = "\n".join(part for part in (analyze.stdout, analyze.stderr) if part).strip()

    return {
        "command_output_mentions_repo_root_contract": _contains_repo_root_failure(combined_output),
        "manifest_exists": manifest_payload is not None,
        "manifest_success_false": manifest_payload is not None and manifest_payload.get("success") is False,
        "manifest_error_mentions_repo_root_contract": (
            manifest_payload is not None
            and _contains_repo_root_failure(str(manifest_payload.get("error", "")))
        ),
        "manifest_current_analysis_absent": (
            manifest_payload is not None and manifest_payload.get("current_analysis") is None
        ),
        "report_exists": report_payload is not None,
        "report_success_false": report_payload is not None and report_payload.get("success") is False,
        "report_error_mentions_repo_root_contract": (
            report_payload is not None
            and _contains_repo_root_failure(str(report_payload.get("error", "")))
        ),
        "report_final_analysis_absent": (
            report_payload is not None and report_payload.get("final_analysis") is None
        ),
        "report_executed_stages_empty": (
            report_payload is not None and report_payload.get("executed_stages") == []
        ),
    }


def _contains_repo_root_failure(text: str) -> bool:
    return any(fragment in text for fragment in EXPECTED_REPO_ROOT_FAILURE_FRAGMENTS)


def _load_json_file(path: Path) -> dict[str, Any] | None:
    if not path.is_file():
        return None
    try:
        payload = json.loads(path.read_text())
    except json.JSONDecodeError:
        return None
    if not isinstance(payload, dict):
        return None
    return payload


if __name__ == "__main__":
    raise SystemExit(main())
