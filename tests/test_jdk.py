import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from arodnap.doctor import _check_java_runtime, _check_wpi_python
from arodnap.runtime import CommandResult, Jdk, JdkResolutionError, resolve_jdk


def _settings(home: str, version: str) -> CommandResult:
    return CommandResult(
        command=("java",),
        cwd=None,
        returncode=0,
        stdout="",
        stderr=f"Property settings:\n    java.home = {home}\n    java.specification.version = {version}\n",
    )


class ResolveJdkTest(unittest.TestCase):
    def test_java_home_wins_and_is_reported_as_given(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            java_home = Path(temp_dir)
            (java_home / "bin").mkdir()
            (java_home / "bin" / "java").write_text("")
            with patch("arodnap.runtime.jdk.run_command", return_value=_settings(f"{java_home}/jre", "1.8")):
                jdk = resolve_jdk({"JAVA_HOME": str(java_home), "PATH": ""})
        self.assertEqual(jdk, Jdk(home=java_home, major_version=8, source="JAVA_HOME"))

    def test_falls_back_to_java_on_path(self) -> None:
        with patch("arodnap.runtime.jdk.shutil.which", return_value="/usr/bin/java"):
            with patch("arodnap.runtime.jdk.run_command", return_value=_settings("/opt/jdk-21", "21")):
                jdk = resolve_jdk({"PATH": "/usr/bin"})
        self.assertEqual(jdk, Jdk(home=Path("/opt/jdk-21"), major_version=21, source="PATH"))

    def test_missing_jdk_fails_clearly(self) -> None:
        with patch("arodnap.runtime.jdk.shutil.which", return_value=None):
            with self.assertRaisesRegex(JdkResolutionError, "No JDK found"):
                resolve_jdk({"PATH": ""})
        with self.assertRaisesRegex(JdkResolutionError, "JAVA_HOME does not point at a JDK"):
            resolve_jdk({"JAVA_HOME": "/does/not/exist"})


class DoctorJdkChecksTest(unittest.TestCase):
    def test_supported_jdk_is_ok(self) -> None:
        with patch("arodnap.doctor.resolve_jdk", return_value=Jdk(Path("/jdk"), 21, "PATH")):
            self.assertEqual(_check_java_runtime().status, "ok")

    def test_jdk_without_wpi_or_rlfixer_support_is_an_error(self) -> None:
        for major in (11, 24):
            with self.subTest(major=major):
                with patch("arodnap.doctor.resolve_jdk", return_value=Jdk(Path("/jdk"), major, "PATH")):
                    check = _check_java_runtime()
                self.assertEqual(check.status, "error")
                self.assertIn("Set JAVA_HOME to JDK 17 or 20 or 21", check.message)

    def test_missing_distutils_python_is_an_error(self) -> None:
        with patch("arodnap.doctor.resolve_dljc_python", return_value=None):
            check = _check_wpi_python()
        self.assertEqual(check.status, "error")
        self.assertIn("ARODNAP_WPI_PYTHON", check.message)


if __name__ == "__main__":
    unittest.main()
