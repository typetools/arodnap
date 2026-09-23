"""End-to-end repair runs with the real toolchain: Gradle, WPI, the Resource Leak Checker,
the Java stage tools, RLFixer, RLPatcher and GNU patch. Nothing is mocked.

These runs take about a minute per fixture, so they are opt-in:

    ARODNAP_E2E=1 python -m unittest tests.test_e2e_real

They need a JDK that both wpi.sh and RLFixer accept (17, 20 or 21) as JAVA_HOME or first
on PATH, `gradle` on PATH, GNU patch, and network access or a warm Gradle cache for
the dependency fixture.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from arodnap.cli.main import main
from arodnap.runtime import RLFIXER_MIN_JDK_MAJOR, WPI_SUPPORTED_JDK_MAJORS, JdkResolutionError, resolve_jdk

FIXTURES_ROOT = Path(__file__).resolve().parent / "fixtures"


def _skip_reason() -> str | None:
    if os.environ.get("ARODNAP_E2E") != "1":
        return "set ARODNAP_E2E=1 to run real end-to-end repairs"
    try:
        jdk = resolve_jdk()
    except JdkResolutionError as exc:
        return str(exc)
    if jdk.major_version not in WPI_SUPPORTED_JDK_MAJORS or jdk.major_version < RLFIXER_MIN_JDK_MAJOR:
        return f"JDK {jdk.major_version} is not supported for analysis"
    if shutil.which("gradle") is None:
        return "gradle is not on PATH"
    return None


@unittest.skipIf(_skip_reason() is not None, _skip_reason() or "")
class RealEndToEndTest(unittest.TestCase):
    def test_baseline_fixture_is_repaired_applied_and_still_compiles(self) -> None:
        report, bundle_files, repo_root = self._repair_and_apply("gradle-pipeline-baseline")

        fixture = "src/main/java/com/arodnap/fixture"
        self.assertEqual(
            set(bundle_files),
            {
                f"{fixture}/DirectLeakExample.java",
                f"{fixture}/TryCatchLeakExample.java",
                f"{fixture}/WrapperMissingClose.java",
                f"{fixture}/OwningFieldReassignment.java",
            },
        )
        self.assertIn("try (", (repo_root / fixture / "DirectLeakExample.java").read_text())
        self.assertIn("try (", (repo_root / fixture / "TryCatchLeakExample.java").read_text())
        self.assertIn("implements AutoCloseable", (repo_root / fixture / "WrapperMissingClose.java").read_text())

    def test_leak_through_a_dependency_is_repaired(self) -> None:
        report, bundle_files, repo_root = self._repair_and_apply("gradle-dependency-leak")

        source = "src/main/java/com/arodnap/fixture/DependencyLeakExample.java"
        self.assertEqual(bundle_files, [source])
        self.assertIn("try (FileInputStream in = new FileInputStream(path))", (repo_root / source).read_text())

    def _repair_and_apply(self, fixture_name: str) -> tuple[dict, list[str], Path]:
        temp_root = Path(tempfile.mkdtemp(prefix="arodnap-e2e-"))
        self.addCleanup(shutil.rmtree, temp_root, True)
        repo_root = temp_root / fixture_name
        shutil.copytree(
            FIXTURES_ROOT / fixture_name,
            repo_root,
            ignore=shutil.ignore_patterns("build", ".gradle"),
        )
        out_dir = temp_root / "arodnap-out"
        original = _snapshot(repo_root)

        self.assertEqual(main(["repair", "--out-dir", str(out_dir), str(repo_root)]), 0)

        report = json.loads((out_dir / "report.json").read_text())
        self.assertTrue(report["success"], report.get("error"))
        warning_counts = [run["warning_count"] for run in report["analysis_runs"]]
        self.assertLess(warning_counts[-1], warning_counts[0], f"no warnings were repaired: {warning_counts}")
        self.assertEqual(_snapshot(repo_root), original, "repair must not modify the original repository")

        [entry] = json.loads((out_dir / "patches" / "manifest.json").read_text())["patches"]
        self.assertEqual(
            main(["apply", "--out-dir", str(out_dir), "--patch-dir", str(out_dir / "patches"), str(repo_root)]),
            0,
        )
        compiled = subprocess.run(
            ["gradle", "--no-daemon", "--console=plain", "-q", "compileJava"],
            cwd=repo_root,
            capture_output=True,
            text=True,
        )
        self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
        return report, entry["changed_files"], repo_root


def _snapshot(root: Path) -> dict[str, bytes]:
    return {
        path.relative_to(root).as_posix(): path.read_bytes()
        for path in sorted((root / "src").rglob("*"))
        if path.is_file()
    }


if __name__ == "__main__":
    unittest.main()
