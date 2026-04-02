from __future__ import annotations

import io
import json
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

from arodnap.validation_regression import (
    STEP1_TEST_MODULES,
    RegressionRunResult,
    RegressionRunnerError,
    main,
    run_step1_regression,
)
from arodnap.validation_session import ValidationSession, ValidationSessionConfig
from arodnap.validation_support import ValidationCommandResult


class ValidationRegressionTest(unittest.TestCase):
    def test_run_step1_regression_uses_exact_rulebook_suite_in_order(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            session = ValidationSession(
                python_executable=(temp_root / "python3").resolve(),
                python_version="Python 3.11.9",
                java_version='openjdk version "21.0.2" 2024-01-16',
                gradle_user_home=(temp_root / "gradle-home").resolve(),
                validation_root=(temp_root / "validation-root").resolve(),
            )
            config = ValidationSessionConfig(
                python_executable=session.python_executable,
                gradle_user_home=session.gradle_user_home,
                validation_root=session.validation_root,
            )
            with patch("arodnap.validation_regression.setup_validation_session", return_value=session):
                with patch(
                    "arodnap.validation_regression.run_validation_command",
                    return_value=ValidationCommandResult(
                        label="step1-regression",
                        command=[str(session.python_executable), "-m", "unittest", *STEP1_TEST_MODULES],
                        cwd=temp_root.resolve(),
                        exit_code=0,
                        success=True,
                        stdout="OK\n",
                        stderr="",
                        invocation_error=None,
                    ),
                ) as run_mock:
                    result = run_step1_regression(config, env={"PATH": "/usr/bin"}, cwd=temp_root)

        self.assertTrue(result.success)
        self.assertEqual(result.exit_code, 0)
        self.assertEqual(result.command, [str(session.python_executable), "-m", "unittest", *STEP1_TEST_MODULES])
        self.assertEqual(result.test_modules, list(STEP1_TEST_MODULES))
        self.assertEqual(run_mock.call_args.kwargs["cwd"], temp_root.resolve())
        self.assertEqual(run_mock.call_args.kwargs["env"]["ARODNAP_PYTHON"], str(session.python_executable))
        self.assertEqual(run_mock.call_args.kwargs["env"]["GRADLE_USER_HOME"], str(session.gradle_user_home))
        self.assertEqual(
            run_mock.call_args.kwargs["env"]["ARODNAP_VALIDATION_ROOT"],
            str(session.validation_root),
        )

    def test_run_step1_regression_captures_nonzero_exit_without_raising(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            session = ValidationSession(
                python_executable=(temp_root / "python3").resolve(),
                python_version="Python 3.11.9",
                java_version='openjdk version "21.0.2" 2024-01-16',
                gradle_user_home=(temp_root / "gradle-home").resolve(),
                validation_root=(temp_root / "validation-root").resolve(),
            )
            config = ValidationSessionConfig(
                python_executable=session.python_executable,
                gradle_user_home=session.gradle_user_home,
                validation_root=session.validation_root,
            )

            with patch("arodnap.validation_regression.setup_validation_session", return_value=session):
                with patch(
                    "arodnap.validation_regression.run_validation_command",
                    return_value=ValidationCommandResult(
                        label="step1-regression",
                        command=[str(session.python_executable), "-m", "unittest", *STEP1_TEST_MODULES],
                        cwd=temp_root.resolve(),
                        exit_code=1,
                        success=False,
                        stdout="FAILED\n",
                        stderr="traceback\n",
                    ),
                ):
                    result = run_step1_regression(config, cwd=temp_root)

        self.assertFalse(result.success)
        self.assertEqual(result.exit_code, 1)
        self.assertEqual(result.stdout, "FAILED\n")
        self.assertEqual(result.stderr, "traceback\n")

    def test_run_step1_regression_fails_closed_if_runner_cannot_start(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            session = ValidationSession(
                python_executable=(temp_root / "python3").resolve(),
                python_version="Python 3.11.9",
                java_version='openjdk version "21.0.2" 2024-01-16',
                gradle_user_home=(temp_root / "gradle-home").resolve(),
                validation_root=(temp_root / "validation-root").resolve(),
            )
            config = ValidationSessionConfig(
                python_executable=session.python_executable,
                gradle_user_home=session.gradle_user_home,
                validation_root=session.validation_root,
            )

            with patch("arodnap.validation_regression.setup_validation_session", return_value=session):
                with patch(
                    "arodnap.validation_regression.run_validation_command",
                    return_value=ValidationCommandResult(
                        label="step1-regression",
                        command=[str(session.python_executable), "-m", "unittest", *STEP1_TEST_MODULES],
                        cwd=temp_root.resolve(),
                        exit_code=None,
                        success=False,
                        stdout="",
                        stderr="",
                        invocation_error="python missing",
                    ),
                ):
                    with self.assertRaisesRegex(RegressionRunnerError, "Failed to invoke Step 1 regression"):
                        run_step1_regression(config, cwd=temp_root)

    def test_main_prints_json_summary_and_returns_test_exit_code(self) -> None:
        stdout = io.StringIO()
        stderr = io.StringIO()
        result = RegressionRunResult(
            python_executable=Path("/python"),
            python_version="Python 3.11.9",
            java_version='openjdk version "21.0.2" 2024-01-16',
            gradle_user_home=Path("/gradle-home"),
            validation_root=Path("/validation-root"),
            cwd=Path("/repo"),
            command=["/python", "-m", "unittest", *STEP1_TEST_MODULES],
            test_modules=list(STEP1_TEST_MODULES),
            exit_code=1,
            success=False,
            stdout="FAILED\n",
            stderr="traceback\n",
        )

        with patch("arodnap.validation_regression.run_step1_regression", return_value=result):
            with redirect_stdout(stdout), redirect_stderr(stderr):
                exit_code = main([])

        self.assertEqual(exit_code, 1)
        payload = json.loads(stdout.getvalue())
        self.assertFalse(payload["success"])
        self.assertEqual(payload["test_modules"], list(STEP1_TEST_MODULES))
        self.assertIn("Step 1 regression failed with exit code 1.", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
