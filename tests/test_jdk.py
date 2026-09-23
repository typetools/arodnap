import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from arodnap.doctor import _check_java_runtime, _check_wpi_gradle_jdk, _check_wpi_python
from arodnap.analysis.wpi_runner import wpi_supported_jdk_majors
from arodnap.runtime import CommandResult, Jdk, JdkResolutionError, resolve_jdk

_CF_3_49 = Path(__file__).resolve().parents[1] / "checker_framework" / "checker-framework-3.49.0"


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


class WpiSupportedJdksTest(unittest.TestCase):
    def test_reads_supported_majors_from_wpi_script(self) -> None:
        self.assertEqual(wpi_supported_jdk_majors(_CF_3_49), (8, 11, 17, 20, 21))

        with tempfile.TemporaryDirectory() as temp_dir:
            cf_root = Path(temp_dir)
            (cf_root / "checker" / "bin").mkdir(parents=True)
            (cf_root / "checker" / "bin" / "wpi.sh").write_text(
                '  if [ "${has_java21}" = "no" ] && [ "${java_version}" = 21 ]; then\n'
                '  if [ "${has_java25}" = "no" ] && [ "${java_version}" = 25 ]; then\n'
            )
            self.assertEqual(wpi_supported_jdk_majors(cf_root), (21, 25))
            self.assertEqual(wpi_supported_jdk_majors(cf_root / "missing"), ())


class DoctorJdkChecksTest(unittest.TestCase):
    def test_supported_jdk_is_ok(self) -> None:
        with patch("arodnap.doctor.resolve_jdk", return_value=Jdk(Path("/jdk"), 21, "PATH")):
            self.assertEqual(_check_java_runtime(_CF_3_49).status, "ok")

    def test_jdk_without_wpi_or_rlfixer_support_is_an_error(self) -> None:
        for major in (11, 24):
            with self.subTest(major=major):
                with patch("arodnap.doctor.resolve_jdk", return_value=Jdk(Path("/jdk"), major, "PATH")):
                    check = _check_java_runtime(_CF_3_49)
                self.assertEqual(check.status, "error")
                self.assertIn("Set JAVA_HOME to JDK 17 or 20 or 21", check.message)

    def test_gradle_on_newer_jdk_needs_java21_home_when_wpi_hardcodes_it(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            cf_root = Path(temp_dir)
            (cf_root / "checker" / "bin").mkdir(parents=True)
            (cf_root / "checker" / "bin" / "wpi.sh").write_text(
                'CLEAN_CMD="${GRADLE_EXEC} clean -Dorg.gradle.java.home=${JAVA21_HOME}"\n'
            )
            with patch("arodnap.doctor.resolve_jdk", return_value=Jdk(Path("/jdk-24"), 24, "JAVA_HOME")):
                with patch.dict("os.environ", {}, clear=False) as env:
                    env.pop("JAVA21_HOME", None)
                    self.assertEqual(_check_wpi_gradle_jdk(cf_root).status, "error")
                with patch.dict("os.environ", {"JAVA21_HOME": "/jdk-21"}):
                    self.assertEqual(_check_wpi_gradle_jdk(cf_root).status, "ok")
            with patch("arodnap.doctor.resolve_jdk", return_value=Jdk(Path("/jdk-21"), 21, "PATH")):
                self.assertEqual(_check_wpi_gradle_jdk(cf_root).status, "ok")
        # A wpi.sh that runs Gradle on JAVA_HOME needs nothing extra.
        self.assertEqual(_check_wpi_gradle_jdk(_CF_3_49).status, "ok")

    def test_missing_distutils_python_is_an_error(self) -> None:
        with patch("arodnap.doctor.resolve_dljc_python", return_value=None):
            check = _check_wpi_python()
        self.assertEqual(check.status, "error")
        self.assertIn("ARODNAP_WPI_PYTHON", check.message)


if __name__ == "__main__":
    unittest.main()
