from __future__ import annotations

import io
import json
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

from arodnap.validation_session import ValidationSession, ValidationSessionConfig
from arodnap.validation_smoke_secondary import (
    SECONDARY_REPO_DIRNAME,
    SECONDARY_REPO_NAME,
    SECONDARY_REPO_SHA,
    Step3SmokeResult,
    main,
    run_step3_secondary_smoke,
)
from arodnap.validation_support import ValidationCommandResult, tool_repo_root


class ValidationSmokeSecondaryTest(unittest.TestCase):
    def test_run_step3_secondary_smoke_executes_default_rulebook_sequence(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            session = self._make_session(temp_root)
            config = ValidationSessionConfig(
                python_executable=session.python_executable,
                gradle_user_home=session.gradle_user_home,
                validation_root=session.validation_root,
            )
            repo_root = session.validation_root / SECONDARY_REPO_DIRNAME
            out_analyze = session.validation_root / "out-gitpod-analyze"
            out_infer = session.validation_root / "out-gitpod-infer"
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
                    (out_infer / "diagnostics").mkdir(parents=True)
                    (out_infer / "manifest.json").write_text("{}\n")
                    (out_infer / "report.json").write_text("{}\n")
                    (out_infer / "inference" / "initial").mkdir(parents=True)
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

            with patch("arodnap.validation_smoke_secondary.setup_validation_session", return_value=session):
                with patch("arodnap.validation_smoke_secondary.run_validation_command", side_effect=fake_run):
                    result = run_step3_secondary_smoke(config, env={"PATH": "/usr/bin"})

        self.assertTrue(result.success)
        self.assertFalse(result.repair_requested)
        self.assertEqual(result.repo_name, SECONDARY_REPO_NAME)
        self.assertEqual(result.pinned_sha, SECONDARY_REPO_SHA)
        self.assertEqual(
            command_log,
            [
                "clone",
                "fetch-pinned-sha",
                "checkout-pinned-sha",
                "gradle-sanity-build",
                "analyze",
                "infer",
            ],
        )
        self.assertTrue(all(result.artifact_checks.values()))
        self.assertEqual(command_cwds["clone"], session.validation_root.resolve())
        self.assertEqual(command_cwds["gradle-sanity-build"], session.validation_root.resolve())
        self.assertEqual(command_cwds["analyze"], tool_repo_root())
        self.assertEqual(command_cwds["infer"], tool_repo_root())

    def test_run_step3_secondary_smoke_optional_repair_is_opt_in(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            session = self._make_session(temp_root)
            config = ValidationSessionConfig(
                python_executable=session.python_executable,
                gradle_user_home=session.gradle_user_home,
                validation_root=session.validation_root,
            )
            repo_root = session.validation_root / SECONDARY_REPO_DIRNAME
            out_analyze = session.validation_root / "out-gitpod-analyze"
            out_infer = session.validation_root / "out-gitpod-infer"
            out_repair = session.validation_root / "out-gitpod-repair"
            command_log: list[str] = []

            def fake_run(command, *, label, cwd, env=None):
                command_log.append(label)
                if label == "clone":
                    repo_root.mkdir(parents=True)
                    (repo_root / "gradlew").write_text("#!/bin/sh\n")
                if label == "analyze":
                    (out_analyze / "diagnostics").mkdir(parents=True)
                    (out_analyze / "manifest.json").write_text("{}\n")
                    (out_analyze / "report.json").write_text("{}\n")
                if label == "infer":
                    (out_infer / "diagnostics").mkdir(parents=True)
                    (out_infer / "manifest.json").write_text("{}\n")
                    (out_infer / "report.json").write_text("{}\n")
                    (out_infer / "inference" / "initial").mkdir(parents=True)
                if label == "repair":
                    (out_repair / "stages").mkdir(parents=True)
                    (out_repair / "patches").mkdir(parents=True)
                    (out_repair / "patches" / "manifest.json").write_text("{}\n")
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

            with patch("arodnap.validation_smoke_secondary.setup_validation_session", return_value=session):
                with patch("arodnap.validation_smoke_secondary.run_validation_command", side_effect=fake_run):
                    result = run_step3_secondary_smoke(config, include_repair=True)

        self.assertTrue(result.success)
        self.assertTrue(result.repair_requested)
        self.assertIn("repair", command_log)
        self.assertTrue(result.artifact_checks["out-gitpod-repair/stages/"])
        self.assertTrue(result.artifact_checks["out-gitpod-repair/patches/manifest.json"])

    def test_run_step3_secondary_smoke_stops_on_infer_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            session = self._make_session(temp_root)
            config = ValidationSessionConfig(
                python_executable=session.python_executable,
                gradle_user_home=session.gradle_user_home,
                validation_root=session.validation_root,
            )

            def fake_run(command, *, label, cwd, env=None):
                if label == "clone":
                    repo_root = session.validation_root / SECONDARY_REPO_DIRNAME
                    repo_root.mkdir(parents=True)
                    (repo_root / "gradlew").write_text("#!/bin/sh\n")
                if label == "infer":
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
                if label == "analyze":
                    out_analyze = session.validation_root / "out-gitpod-analyze"
                    (out_analyze / "diagnostics").mkdir(parents=True)
                    (out_analyze / "manifest.json").write_text("{}\n")
                    (out_analyze / "report.json").write_text("{}\n")
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

            with patch("arodnap.validation_smoke_secondary.setup_validation_session", return_value=session):
                with patch("arodnap.validation_smoke_secondary.run_validation_command", side_effect=fake_run):
                    result = run_step3_secondary_smoke(config)

        self.assertFalse(result.success)
        self.assertEqual(result.overall_exit_code, 1)
        self.assertIn("`arodnap infer`", result.failure_reason)
        self.assertEqual(
            [command.label for command in result.commands],
            [
                "clone",
                "fetch-pinned-sha",
                "checkout-pinned-sha",
                "gradle-sanity-build",
                "analyze",
                "infer",
            ],
        )

    def test_main_passes_include_repair_flag_and_prints_json_summary(self) -> None:
        stdout = io.StringIO()
        stderr = io.StringIO()
        result = self._make_result(success=True, failure_reason=None, overall_exit_code=0, repair_requested=True)

        with patch("arodnap.validation_smoke_secondary.run_step3_secondary_smoke", return_value=result) as runner:
            with redirect_stdout(stdout), redirect_stderr(stderr):
                exit_code = main(["--include-repair"])

        self.assertEqual(exit_code, 0)
        self.assertTrue(runner.call_args.kwargs["include_repair"])
        payload = json.loads(stdout.getvalue())
        self.assertTrue(payload["repair_requested"])
        self.assertEqual(payload["repo_name"], SECONDARY_REPO_NAME)
        self.assertEqual(stderr.getvalue(), "")

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

    def _make_result(
        self,
        *,
        success: bool,
        failure_reason: str | None,
        overall_exit_code: int,
        repair_requested: bool,
    ) -> Step3SmokeResult:
        return Step3SmokeResult(
            repo_name=SECONDARY_REPO_NAME,
            pinned_sha=SECONDARY_REPO_SHA,
            repo_root=Path("/tmp/repo"),
            python_executable=Path("/python"),
            python_version="Python 3.11.9",
            java_version='openjdk version "21.0.2" 2024-01-16',
            gradle_user_home=Path("/gradle-home"),
            validation_root=Path("/validation-root"),
            commands=[],
            artifact_checks={},
            repair_requested=repair_requested,
            success=success,
            failure_reason=failure_reason,
            overall_exit_code=overall_exit_code,
        )


if __name__ == "__main__":
    unittest.main()
