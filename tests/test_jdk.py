import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from arodnap.analysis.checker_framework import (
    CheckerFrameworkError,
    minimum_jdk_major,
    resolve_analysis_jdk,
    tested_jdk_majors,
)
from arodnap.doctor import _check_java_runtime
from arodnap.runtime import CommandResult, Jdk, JdkResolutionError, resolve_jdk

_CHECKER_FRAMEWORK = Path(__file__).resolve().parents[1] / "checker_framework"
_CF_4_2_3 = _CHECKER_FRAMEWORK / "checker-framework-4.2.3"
_CF_3_49 = _CHECKER_FRAMEWORK / "checker-framework-3.49.0"


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


class CheckerFrameworkJdkTest(unittest.TestCase):
    def test_minimum_jdk_is_read_from_checker_jar(self) -> None:
        self.assertEqual(minimum_jdk_major(_CF_4_2_3), 17)
        self.assertEqual(minimum_jdk_major(_CF_3_49), 8)

    def test_tested_jdks_are_read_from_the_distributions_wpi_script(self) -> None:
        self.assertEqual(tested_jdk_majors(_CF_4_2_3), (8, 11, 17, 21, 24, 25, 26))
        self.assertEqual(tested_jdk_majors(_CF_3_49), (8, 11, 17, 20, 21))
        self.assertEqual(tested_jdk_majors(_CHECKER_FRAMEWORK / "missing"), ())

    def test_analysis_jdk_must_satisfy_checker_framework_and_rlfixer(self) -> None:
        # CF 3.49 runs on JDK 8, but RLFixer needs 17.
        for cf_root, major, ok in ((_CF_4_2_3, 17, True), (_CF_4_2_3, 27, True), (_CF_3_49, 11, False)):
            with self.subTest(cf=cf_root.name, major=major):
                with patch(
                    "arodnap.analysis.checker_framework.resolve_jdk",
                    return_value=Jdk(Path("/jdk"), major, "PATH"),
                ):
                    if ok:
                        self.assertEqual(resolve_analysis_jdk(cf_root).major_version, major)
                    else:
                        with self.assertRaisesRegex(CheckerFrameworkError, "needs JDK 17 or newer"):
                            resolve_analysis_jdk(cf_root)


class DoctorJdkCheckTest(unittest.TestCase):
    def _check(self, major: int):
        with patch(
            "arodnap.analysis.checker_framework.resolve_jdk",
            return_value=Jdk(Path("/jdk"), major, "JAVA_HOME"),
        ):
            return _check_java_runtime(_CF_4_2_3)

    def test_tested_jdks_are_ok(self) -> None:
        for major in (17, 21, 24, 26):
            with self.subTest(major=major):
                self.assertEqual(self._check(major).status, "ok")

    def test_jdk_newer_than_tested_is_only_a_warning(self) -> None:
        check = self._check(27)
        self.assertEqual(check.status, "warning")
        self.assertIn("newer than the JDKs checker-framework-4.2.3 is tested on", check.message)

    def test_too_old_jdk_is_an_error(self) -> None:
        check = self._check(11)
        self.assertEqual(check.status, "error")
        self.assertIn("needs JDK 17 or newer", check.message)


if __name__ == "__main__":
    unittest.main()
