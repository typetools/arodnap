from __future__ import annotations

import io
import json
import subprocess
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

from arodnap.validation_session import (
    ARODNAP_PYTHON_ENV,
    ARODNAP_VALIDATION_ROOT_ENV,
    GRADLE_USER_HOME_ENV,
    ValidationSession,
    ValidationSessionConfig,
    ValidationSessionError,
    build_validation_session_config,
    main,
    setup_validation_session,
)


class ValidationSessionTest(unittest.TestCase):
    def test_setup_validation_session_records_versions_and_creates_directories(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            config = ValidationSessionConfig(
                python_executable=(temp_root / "python3").resolve(),
                gradle_user_home=(temp_root / "gradle-home").resolve(),
                validation_root=(temp_root / "validation-root").resolve(),
            )

            with patch(
                "arodnap.validation_session.subprocess.run",
                side_effect=[
                    subprocess.CompletedProcess(
                        [str(config.python_executable), "--version"],
                        0,
                        stdout="Python 3.11.9\n",
                        stderr="",
                    ),
                    subprocess.CompletedProcess(
                        ["java", "-version"],
                        0,
                        stdout="",
                        stderr='openjdk version "21.0.2" 2024-01-16\n',
                    ),
                ],
            ) as run_mock:
                session = setup_validation_session(config)

            self.assertTrue(config.gradle_user_home.is_dir())
            self.assertTrue(config.validation_root.is_dir())
            self.assertEqual(session.python_version, "Python 3.11.9")
            self.assertEqual(session.java_version, 'openjdk version "21.0.2" 2024-01-16')
            self.assertEqual(
                run_mock.call_args_list[0].args[0],
                [str(config.python_executable), "--version"],
            )
            self.assertEqual(run_mock.call_args_list[1].args[0], ["java", "-version"])

    def test_setup_validation_session_rejects_python_below_3_10(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            config = ValidationSessionConfig(
                python_executable=(temp_root / "python3").resolve(),
                gradle_user_home=(temp_root / "gradle-home").resolve(),
                validation_root=(temp_root / "validation-root").resolve(),
            )

            with patch(
                "arodnap.validation_session.subprocess.run",
                return_value=subprocess.CompletedProcess(
                    [str(config.python_executable), "--version"],
                    0,
                    stdout="Python 3.9.18\n",
                    stderr="",
                ),
            ):
                with self.assertRaisesRegex(ValidationSessionError, "Python 3.10\\+ is required"):
                    setup_validation_session(config)

    def test_setup_validation_session_rejects_missing_java(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            config = ValidationSessionConfig(
                python_executable=(temp_root / "python3").resolve(),
                gradle_user_home=(temp_root / "gradle-home").resolve(),
                validation_root=(temp_root / "validation-root").resolve(),
            )

            def fake_run(command, **kwargs):
                if command[0] == str(config.python_executable):
                    return subprocess.CompletedProcess(command, 0, stdout="Python 3.10.14\n", stderr="")
                raise FileNotFoundError(command[0])

            with patch("arodnap.validation_session.subprocess.run", side_effect=fake_run):
                with self.assertRaisesRegex(ValidationSessionError, "Java is required"):
                    setup_validation_session(config)

    def test_build_validation_session_config_uses_environment_overrides(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            env = {
                ARODNAP_PYTHON_ENV: str(temp_root / "custom-python"),
                GRADLE_USER_HOME_ENV: str(temp_root / "custom-gradle"),
                ARODNAP_VALIDATION_ROOT_ENV: str(temp_root / "custom-validation"),
            }

            config = build_validation_session_config(env)

            self.assertEqual(config.python_executable, (temp_root / "custom-python").resolve())
            self.assertEqual(config.gradle_user_home, (temp_root / "custom-gradle").resolve())
            self.assertEqual(config.validation_root, (temp_root / "custom-validation").resolve())

    def test_main_prints_json_summary(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            stdout = io.StringIO()
            session = ValidationSession(
                python_executable=(temp_root / "python3").resolve(),
                python_version="Python 3.11.9",
                java_version='openjdk version "21.0.2" 2024-01-16',
                gradle_user_home=(temp_root / "gradle-home").resolve(),
                validation_root=(temp_root / "validation-root").resolve(),
            )

            with patch(
                "arodnap.validation_session.setup_validation_session",
                return_value=session,
            ):
                with redirect_stdout(stdout):
                    exit_code = main(
                        [
                            "--python",
                            str(temp_root / "python3"),
                            "--gradle-user-home",
                            str(temp_root / "gradle-home"),
                            "--validation-root",
                            str(temp_root / "validation-root"),
                        ]
                    )

            self.assertEqual(exit_code, 0)
            payload = json.loads(stdout.getvalue())
            self.assertEqual(payload["python_version"], "Python 3.11.9")
            self.assertEqual(payload["gradle_user_home"], str((temp_root / "gradle-home").resolve()))


if __name__ == "__main__":
    unittest.main()
