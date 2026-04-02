from __future__ import annotations

import io
import json
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

from arodnap.validation_negative_maven_shape import (
    NEGATIVE_REPO_DIRNAME,
    NEGATIVE_REPO_NAME,
    NEGATIVE_REPO_SHA,
    Step5MavenShapeNegativeResult,
    main,
    run_step5_maven_shape_negative,
)
from arodnap.validation_session import ValidationSession, ValidationSessionConfig
from arodnap.validation_support import ValidationCommandResult, tool_repo_root


class ValidationNegativeMavenShapeTest(unittest.TestCase):
    def test_run_step5_maven_shape_negative_accepts_explicit_early_rejection(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            session = self._make_session(temp_root)
            config = ValidationSessionConfig(
                python_executable=session.python_executable,
                gradle_user_home=session.gradle_user_home,
                validation_root=session.validation_root,
            )
            repo_root = session.validation_root / NEGATIVE_REPO_DIRNAME
            out_analyze = session.validation_root / "out-zookeeper-analyze"
            command_log: list[str] = []
            command_cwds: dict[str, Path] = {}

            def fake_run(command, *, label, cwd, env=None):
                command_log.append(label)
                command_cwds[label] = cwd.resolve()
                if label == "clone":
                    repo_root.mkdir(parents=True)
                if label == "analyze":
                    self._write_failed_analysis_outputs(
                        out_analyze,
                        error_message="Gradle repo root must contain one of ('build.gradle', 'build.gradle.kts')",
                    )
                    return ValidationCommandResult(
                        label=label,
                        command=list(command),
                        cwd=cwd.resolve(),
                        exit_code=1,
                        success=False,
                        stdout="",
                        stderr=(
                            "arodnap.analysis.analyze.AnalyzeError: "
                            "Gradle repo root must contain one of ('build.gradle', 'build.gradle.kts')\n"
                        ),
                        invocation_error=None,
                    )
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

            with patch("arodnap.validation_negative_maven_shape.setup_validation_session", return_value=session):
                with patch("arodnap.validation_negative_maven_shape.run_validation_command", side_effect=fake_run):
                    result = run_step5_maven_shape_negative(config, env={"PATH": "/usr/bin"})

        self.assertTrue(result.success)
        self.assertTrue(result.analyze_failed)
        self.assertTrue(result.repo_clean)
        self.assertEqual(result.repo_status, "")
        self.assertTrue(all(result.contract_checks.values()))
        self.assertEqual(
            command_log,
            [
                "clone",
                "fetch-pinned-sha",
                "checkout-pinned-sha",
                "analyze",
                "repo-status",
            ],
        )
        self.assertEqual(command_cwds["clone"], session.validation_root.resolve())
        self.assertEqual(command_cwds["analyze"], tool_repo_root())
        self.assertEqual(command_cwds["repo-status"], session.validation_root.resolve())

    def test_run_step5_maven_shape_negative_fails_when_analyze_succeeds(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            session = self._make_session(temp_root)
            config = ValidationSessionConfig(
                python_executable=session.python_executable,
                gradle_user_home=session.gradle_user_home,
                validation_root=session.validation_root,
            )
            repo_root = session.validation_root / NEGATIVE_REPO_DIRNAME

            def fake_run(command, *, label, cwd, env=None):
                if label == "clone":
                    repo_root.mkdir(parents=True)
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

            with patch("arodnap.validation_negative_maven_shape.setup_validation_session", return_value=session):
                with patch("arodnap.validation_negative_maven_shape.run_validation_command", side_effect=fake_run):
                    result = run_step5_maven_shape_negative(config)

        self.assertFalse(result.success)
        self.assertFalse(result.analyze_failed)
        self.assertIn("expected `arodnap analyze` to reject", result.failure_reason)

    def test_run_step5_maven_shape_negative_fails_when_rejection_is_ambiguous(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            session = self._make_session(temp_root)
            config = ValidationSessionConfig(
                python_executable=session.python_executable,
                gradle_user_home=session.gradle_user_home,
                validation_root=session.validation_root,
            )
            repo_root = session.validation_root / NEGATIVE_REPO_DIRNAME
            out_analyze = session.validation_root / "out-zookeeper-analyze"

            def fake_run(command, *, label, cwd, env=None):
                if label == "clone":
                    repo_root.mkdir(parents=True)
                if label == "analyze":
                    self._write_failed_analysis_outputs(
                        out_analyze,
                        error_message="something else failed",
                        final_analysis={"label": "initial"},
                    )
                    return ValidationCommandResult(
                        label=label,
                        command=list(command),
                        cwd=cwd.resolve(),
                        exit_code=1,
                        success=False,
                        stdout="",
                        stderr="RuntimeError: something else failed\n",
                        invocation_error=None,
                    )
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

            with patch("arodnap.validation_negative_maven_shape.setup_validation_session", return_value=session):
                with patch("arodnap.validation_negative_maven_shape.run_validation_command", side_effect=fake_run):
                    result = run_step5_maven_shape_negative(config)

        self.assertFalse(result.success)
        self.assertTrue(result.analyze_failed)
        self.assertIn("expected explicit early Maven multi-module", result.failure_reason)
        self.assertFalse(result.contract_checks["command_output_mentions_maven_shape_contract"])
        self.assertFalse(result.contract_checks["report_final_analysis_absent"])

    def test_run_step5_maven_shape_negative_fails_when_repo_is_dirty(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            session = self._make_session(temp_root)
            config = ValidationSessionConfig(
                python_executable=session.python_executable,
                gradle_user_home=session.gradle_user_home,
                validation_root=session.validation_root,
            )
            repo_root = session.validation_root / NEGATIVE_REPO_DIRNAME
            out_analyze = session.validation_root / "out-zookeeper-analyze"

            def fake_run(command, *, label, cwd, env=None):
                if label == "clone":
                    repo_root.mkdir(parents=True)
                if label == "analyze":
                    self._write_failed_analysis_outputs(
                        out_analyze,
                        error_message="Gradle repo root must contain one of ('build.gradle', 'build.gradle.kts')",
                    )
                    return ValidationCommandResult(
                        label=label,
                        command=list(command),
                        cwd=cwd.resolve(),
                        exit_code=1,
                        success=False,
                        stdout="",
                        stderr="AnalyzeError: Gradle repo root must contain one of ('build.gradle', 'build.gradle.kts')\n",
                        invocation_error=None,
                    )
                if label == "repo-status":
                    return ValidationCommandResult(
                        label=label,
                        command=list(command),
                        cwd=cwd.resolve(),
                        exit_code=0,
                        success=True,
                        stdout=" M README.md\n",
                        stderr="",
                        invocation_error=None,
                    )
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

            with patch("arodnap.validation_negative_maven_shape.setup_validation_session", return_value=session):
                with patch("arodnap.validation_negative_maven_shape.run_validation_command", side_effect=fake_run):
                    result = run_step5_maven_shape_negative(config)

        self.assertFalse(result.success)
        self.assertTrue(result.analyze_failed)
        self.assertFalse(result.repo_clean)
        self.assertIn("unexpected repo mutations", result.failure_reason)

    def test_main_prints_json_summary(self) -> None:
        stdout = io.StringIO()
        stderr = io.StringIO()
        result = self._make_result(success=True, failure_reason=None, overall_exit_code=0)

        with patch("arodnap.validation_negative_maven_shape.run_step5_maven_shape_negative", return_value=result):
            with redirect_stdout(stdout), redirect_stderr(stderr):
                exit_code = main([])

        self.assertEqual(exit_code, 0)
        payload = json.loads(stdout.getvalue())
        self.assertEqual(payload["repo_name"], NEGATIVE_REPO_NAME)
        self.assertTrue(payload["success"])
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
    ) -> Step5MavenShapeNegativeResult:
        return Step5MavenShapeNegativeResult(
            repo_name=NEGATIVE_REPO_NAME,
            pinned_sha=NEGATIVE_REPO_SHA,
            repo_root=Path("/tmp/repo"),
            python_executable=Path("/python"),
            python_version="Python 3.11.9",
            java_version='openjdk version "21.0.2" 2024-01-16',
            gradle_user_home=Path("/gradle-home"),
            validation_root=Path("/validation-root"),
            commands=[],
            analyze_failed=not success,
            repo_status="",
            repo_clean=True,
            contract_checks={},
            success=success,
            failure_reason=failure_reason,
            overall_exit_code=overall_exit_code,
        )

    def _write_failed_analysis_outputs(
        self,
        out_analyze: Path,
        *,
        error_message: str,
        final_analysis: dict[str, object] | None = None,
    ) -> None:
        out_analyze.mkdir(parents=True, exist_ok=True)
        (out_analyze / "manifest.json").write_text(
            json.dumps(
                {
                    "current_analysis": None,
                    "error": error_message,
                    "stage_history": [],
                    "success": False,
                }
            )
            + "\n"
        )
        (out_analyze / "report.json").write_text(
            json.dumps(
                {
                    "error": error_message,
                    "executed_stages": [],
                    "final_analysis": final_analysis,
                    "success": False,
                }
            )
            + "\n"
        )


if __name__ == "__main__":
    unittest.main()
