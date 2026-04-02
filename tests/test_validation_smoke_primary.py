from __future__ import annotations

import io
import json
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

from arodnap.validation_session import ValidationSession, ValidationSessionConfig
from arodnap.validation_smoke_primary import (
    PRIMARY_REPO_DIRNAME,
    PRIMARY_REPO_NAME,
    PRIMARY_REPO_SHA,
    Step2SmokeResult,
    main,
    run_step2_primary_smoke,
)
from arodnap.validation_support import ValidationCommandResult
from arodnap.validation_support import tool_repo_root


class ValidationSmokePrimaryTest(unittest.TestCase):
    def test_run_step2_primary_smoke_executes_rulebook_sequence_and_records_success(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            session = self._make_session(temp_root)
            config = ValidationSessionConfig(
                python_executable=session.python_executable,
                gradle_user_home=session.gradle_user_home,
                validation_root=session.validation_root,
            )
            repo_root = session.validation_root / PRIMARY_REPO_DIRNAME
            out_analyze = session.validation_root / "out-codecov-analyze"
            out_infer = session.validation_root / "out-codecov-infer"
            out_repair = session.validation_root / "out-codecov-repair"
            command_log: list[str] = []
            command_cwds: dict[str, Path] = {}

            def fake_run(command, *, label, cwd, env=None):
                command_log.append(label)
                command_cwds[label] = cwd.resolve()
                if label == "clone":
                    repo_root.mkdir(parents=True)
                    (repo_root / "gradlew").write_text("#!/bin/sh\n")
                if label == "analyze":
                    (out_analyze / "diagnostics").mkdir(parents=True)
                    (out_analyze / "manifest.json").write_text("{}\n")
                    (out_analyze / "report.json").write_text("{}\n")
                if label == "infer":
                    (out_infer / "inference" / "initial").mkdir(parents=True)
                if label == "repair":
                    (out_repair / "stages").mkdir(parents=True)
                    (out_repair / "patches").mkdir(parents=True)
                    (out_repair / "patches" / "manifest.json").write_text("{}\n")
                stdout = ""
                if label in {"repo-status-before-apply", "repo-status-after-apply"}:
                    stdout = ""
                return ValidationCommandResult(
                    label=label,
                    command=list(command),
                    cwd=cwd.resolve(),
                    exit_code=0,
                    success=True,
                    stdout=stdout,
                    stderr="",
                    invocation_error=None,
                )

            with patch("arodnap.validation_smoke_primary.setup_validation_session", return_value=session):
                with patch("arodnap.validation_smoke_primary.run_validation_command", side_effect=fake_run):
                    result = run_step2_primary_smoke(config, env={"PATH": "/usr/bin"})

        self.assertTrue(result.success)
        self.assertEqual(result.repo_name, PRIMARY_REPO_NAME)
        self.assertEqual(result.pinned_sha, PRIMARY_REPO_SHA)
        self.assertEqual(
            command_log,
            [
                "clone",
                "fetch-pinned-sha",
                "checkout-pinned-sha",
                "gradle-sanity-build",
                "analyze",
                "infer",
                "repair",
                "repo-status-before-apply",
                "apply",
                "repo-status-after-apply",
            ],
        )
        self.assertTrue(all(result.artifact_checks.values()))
        self.assertTrue(result.repo_clean_before_apply)
        self.assertTrue(result.repo_clean_after_apply)
        self.assertEqual(command_cwds["clone"], session.validation_root.resolve())
        self.assertEqual(command_cwds["gradle-sanity-build"], session.validation_root.resolve())
        self.assertEqual(command_cwds["analyze"], tool_repo_root())
        self.assertEqual(command_cwds["infer"], tool_repo_root())
        self.assertEqual(command_cwds["repair"], tool_repo_root())
        self.assertEqual(command_cwds["apply"], tool_repo_root())

    def test_run_step2_primary_smoke_stops_on_failed_command(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            session = self._make_session(temp_root)
            config = ValidationSessionConfig(
                python_executable=session.python_executable,
                gradle_user_home=session.gradle_user_home,
                validation_root=session.validation_root,
            )

            def fake_run(command, *, label, cwd, env=None):
                if label == "gradle-sanity-build":
                    return ValidationCommandResult(
                        label=label,
                        command=list(command),
                        cwd=cwd.resolve(),
                        exit_code=1,
                        success=False,
                        stdout="",
                        stderr="boom\n",
                        invocation_error=None,
                    )
                if label == "clone":
                    (session.validation_root / PRIMARY_REPO_DIRNAME).mkdir(parents=True)
                    ((session.validation_root / PRIMARY_REPO_DIRNAME) / "gradlew").write_text("#!/bin/sh\n")
                return ValidationCommandResult(
                    label=label,
                    command=list(command),
                    cwd=cwd.resolve(),
                    exit_code=0,
                    success=True,
                    stdout="",
                    stderr="",
                    invocation_error=None,
                )

            with patch("arodnap.validation_smoke_primary.setup_validation_session", return_value=session):
                with patch("arodnap.validation_smoke_primary.run_validation_command", side_effect=fake_run):
                    result = run_step2_primary_smoke(config)

        self.assertFalse(result.success)
        self.assertEqual(result.overall_exit_code, 1)
        self.assertIn("Gradle sanity build", result.failure_reason)
        self.assertEqual([command.label for command in result.commands], ["clone", "fetch-pinned-sha", "checkout-pinned-sha", "gradle-sanity-build"])

    def test_run_step2_primary_smoke_fails_when_repo_is_dirty_before_apply(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            session = self._make_session(temp_root)
            config = ValidationSessionConfig(
                python_executable=session.python_executable,
                gradle_user_home=session.gradle_user_home,
                validation_root=session.validation_root,
            )
            repo_root = session.validation_root / PRIMARY_REPO_DIRNAME
            out_analyze = session.validation_root / "out-codecov-analyze"
            out_infer = session.validation_root / "out-codecov-infer"
            out_repair = session.validation_root / "out-codecov-repair"

            def fake_run(command, *, label, cwd, env=None):
                if label == "clone":
                    repo_root.mkdir(parents=True)
                    (repo_root / "gradlew").write_text("#!/bin/sh\n")
                if label == "analyze":
                    (out_analyze / "diagnostics").mkdir(parents=True)
                    (out_analyze / "manifest.json").write_text("{}\n")
                    (out_analyze / "report.json").write_text("{}\n")
                if label == "infer":
                    (out_infer / "inference" / "initial").mkdir(parents=True)
                if label == "repair":
                    (out_repair / "stages").mkdir(parents=True)
                    (out_repair / "patches").mkdir(parents=True)
                    (out_repair / "patches" / "manifest.json").write_text("{}\n")
                stdout = " M src/main/java/App.java\n" if label == "repo-status-before-apply" else ""
                return ValidationCommandResult(
                    label=label,
                    command=list(command),
                    cwd=cwd.resolve(),
                    exit_code=0,
                    success=True,
                    stdout=stdout,
                    stderr="",
                    invocation_error=None,
                )

            with patch("arodnap.validation_smoke_primary.setup_validation_session", return_value=session):
                with patch("arodnap.validation_smoke_primary.run_validation_command", side_effect=fake_run):
                    result = run_step2_primary_smoke(config)

        self.assertFalse(result.success)
        self.assertFalse(result.repo_clean_before_apply)
        self.assertIn("unexpected repo mutations before apply", result.failure_reason)

    def test_main_prints_json_summary_and_failure_reason(self) -> None:
        stdout = io.StringIO()
        stderr = io.StringIO()
        result = self._make_result(success=False, failure_reason="bad smoke", overall_exit_code=1)

        with patch("arodnap.validation_smoke_primary.run_step2_primary_smoke", return_value=result):
            with redirect_stdout(stdout), redirect_stderr(stderr):
                exit_code = main([])

        self.assertEqual(exit_code, 1)
        payload = json.loads(stdout.getvalue())
        self.assertFalse(payload["success"])
        self.assertEqual(payload["repo_name"], PRIMARY_REPO_NAME)
        self.assertIn("bad smoke", stderr.getvalue())

    def _make_session(self, temp_root: Path) -> ValidationSession:
        validation_root = temp_root / "validation-root"
        validation_root.mkdir()
        return ValidationSession(
            python_executable=(temp_root / "python3").resolve(),
            python_version="Python 3.11.9",
            java_version='openjdk version "21.0.2" 2024-01-16',
            gradle_user_home=(temp_root / "gradle-home").resolve(),
            validation_root=validation_root.resolve(),
        )

    def _make_result(self, *, success: bool, failure_reason: str | None, overall_exit_code: int):
        return Step2SmokeResult(
            repo_name=PRIMARY_REPO_NAME,
            pinned_sha=PRIMARY_REPO_SHA,
            repo_root=Path("/tmp/repo"),
            python_executable=Path("/python"),
            python_version="Python 3.11.9",
            java_version='openjdk version "21.0.2" 2024-01-16',
            gradle_user_home=Path("/gradle-home"),
            validation_root=Path("/validation-root"),
            commands=[],
            artifact_checks={},
            repo_status_before_apply=None,
            repo_status_after_apply=None,
            repo_clean_before_apply=None,
            repo_clean_after_apply=None,
            success=success,
            failure_reason=failure_reason,
            overall_exit_code=overall_exit_code,
        )


if __name__ == "__main__":
    unittest.main()
