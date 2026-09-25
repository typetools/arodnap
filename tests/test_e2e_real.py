"""End-to-end repair runs with the real toolchain: Gradle, WPI, the Resource Leak Checker,
the Java stage tools, RLFixer and RLPatcher. Nothing is mocked.

These runs take about a minute per fixture, so they are opt-in:

    ARODNAP_E2E=1 python -m unittest tests.test_e2e_real

They use the Checker Framework from $ARODNAP_CHECKER_FRAMEWORK (default: the bundled
4.2.3) and need a JDK new enough for it and for RLFixer (17+), as JAVA_HOME or first on PATH, `gradle` on PATH, and network access or a warm Gradle cache for
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
from arodnap.analysis.checker_framework import CheckerFrameworkError, resolve_analysis_jdk
from arodnap.orchestrator.config import resolve_cf_root

FIXTURES_ROOT = Path(__file__).resolve().parent / "fixtures"


def _skip_reason() -> str | None:
    if os.environ.get("ARODNAP_E2E") != "1":
        return "set ARODNAP_E2E=1 to run real end-to-end repairs"
    try:
        resolve_analysis_jdk(resolve_cf_root())
    except CheckerFrameworkError as exc:
        return str(exc)
    return None


def _requires(tool: str):
    return unittest.skipIf(shutil.which(tool) is None, f"{tool} is not on PATH")


@unittest.skipIf(_skip_reason() is not None, _skip_reason() or "")
class RealEndToEndTest(unittest.TestCase):
    @_requires("gradle")
    def test_baseline_fixture_is_repaired_applied_and_still_compiles(self) -> None:
        report, bundle_files, repo_root = self._repair_and_apply("gradle-pipeline-baseline", verify=_GRADLE)

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

    @_requires("gradle")
    def test_leak_through_a_dependency_is_repaired(self) -> None:
        report, bundle_files, repo_root = self._repair_and_apply("gradle-dependency-leak", verify=_GRADLE)

        source = "src/main/java/com/arodnap/fixture/DependencyLeakExample.java"
        self.assertEqual(bundle_files, [source])
        self.assertIn("try (FileInputStream in = new FileInputStream(path))", (repo_root / source).read_text())

    @_requires("gradle")
    def test_every_stage_does_its_part(self) -> None:
        report, bundle_files, repo_root = self._repair_and_apply("gradle-pipeline-coverage", verify=_GRADLE)
        out_dir = repo_root.parent / "arodnap-out"

        # Exact counts: a change here means a stage (or an analysis dependency) behaves differently.
        self.assertEqual(
            [(run["label"], run["warning_count"]) for run in report["analysis_runs"]],
            [("initial", 26), ("post_close_injector", 26), ("post_owning_field", 24), ("final", 6)],
        )
        stages = {
            name: json.loads((out_dir / "stages" / name / "stage_result.json").read_text())
            for name in ("close_injector", "owning_field", "rlfixer", "rlpatcher", "bundle")
        }
        self.assertEqual(stages["close_injector"]["changed_files"], ["src/main/java/wrapper/Wrapper.java"])
        self.assertEqual(
            sorted(stages["owning_field"]["changed_files"]),
            ["src/main/java/owning/PackagePrivateSink.java", "src/main/java/owning/PublicSink.java"],
        )
        self.assertEqual(stages["rlfixer"]["notes"], ["RLFixer proposed 20 fix(es) for 24 leak warning(s)."])
        self.assertIn("RLPatcher materialized 18 of 20", stages["rlpatcher"]["notes"][0])
        changed_by_stages = {
            path for name in ("close_injector", "owning_field", "rlpatcher") for path in stages[name]["changed_files"]
        }
        self.assertEqual(set(bundle_files), changed_by_stages)

        # Per-leak results: the counts reconcile with the analysis runs above.
        leaks = report["leaks"]
        self.assertEqual(
            {key: leaks["summary"][key] for key in ("initial", "found_during_repair", "fixed", "remaining")},
            {"initial": 26, "found_during_repair": 1, "fixed": 21, "remaining": 6},
        )
        self.assertEqual(leaks["summary"]["fixed_by_stage"], {"close_injector": 1, "owning_field": 2, "rlpatcher": 18})
        self.assertTrue(Path(leaks["html_report"]).is_file())
        by_file = {warning["file"]: warning for warning in leaks["warnings"]}
        self.assertEqual(by_file["src/main/java/wrapper/Wrapper.java"]["fixed_by"], "close_injector")
        self.assertEqual(
            (by_file["src/main/java/wrapper/WrapperClient.java"]["first_seen"],
             by_file["src/main/java/wrapper/WrapperClient.java"]["fixed_by"]),
            ("post_close_injector", "rlpatcher"),
        )

        # The paper's scenario: the leak in the wrapper's client is fixed once the wrapper is closable.
        client = (repo_root / "src/main/java/wrapper/WrapperClient.java").read_text()
        self.assertIn("try (Wrapper wrapper = new Wrapper(path))", client)
        self.assertIn(
            "\n    private final FileWriter out;\n",
            (repo_root / "src/main/java/owning/PackagePrivateSink.java").read_text(),
        )

    @_requires("gradle")
    def test_every_module_of_a_multi_module_gradle_build_is_repaired(self) -> None:
        report, bundle_files, repo_root = self._repair_and_apply("gradle-multimodule", verify=_GRADLE)

        core = "core/src/main/java/demo/core/FirstByte.java"
        app = "app/src/main/java/demo/app/Report.java"
        self.assertEqual(set(bundle_files), {core, app})
        self.assertIn("try (FileInputStream in = new FileInputStream(path))", (repo_root / core).read_text())
        self.assertIn("try (FileInputStream in = new FileInputStream(path))", (repo_root / app).read_text())

    @_requires("mvn")
    def test_maven_project_is_repaired(self) -> None:
        report, bundle_files, repo_root = self._repair_and_apply(
            "maven-dependency-leak", verify=["mvn", "-q", "-B", "compile"]
        )

        source = "src/main/java/demo/ReadAll.java"
        self.assertEqual(bundle_files, [source])
        self.assertIn("try (FileInputStream in = new FileInputStream(path))", (repo_root / source).read_text())

    @_requires("ant")
    def test_ant_project_with_a_vendored_jar_is_repaired(self) -> None:
        report, bundle_files, repo_root = self._repair_and_apply("ant-vendored-jar", verify=["ant", "-q", "compile"])

        source = "src/demo/Checksum.java"
        self.assertEqual(bundle_files, [source])
        self.assertIn("try (FileInputStream in = new FileInputStream(path))", (repo_root / source).read_text())

    def test_project_built_by_a_javac_script_is_repaired(self) -> None:
        report, bundle_files, repo_root = self._repair_and_apply(
            "javac-script", build_command=["./build.sh"], verify=["./build.sh"]
        )

        source = "src/demo/FirstByte.java"
        self.assertEqual(bundle_files, [source])
        self.assertIn("try (FileInputStream in = new FileInputStream(path))", (repo_root / source).read_text())

    def test_a_leak_returned_through_a_cycle_of_callers_is_left_unfixed(self) -> None:
        report, bundle_files, repo_root = self._repair_and_apply(
            "javac-return-cycle", build_command=["./build.sh"], verify=["./build.sh"]
        )

        self.assertEqual(bundle_files, ["src/demo/FirstByte.java"])
        remaining = [leak for leak in report["leaks"]["warnings"] if leak["status"] == "remaining"]
        self.assertEqual([(leak["file"], leak["reason"]) for leak in remaining],
                         [("src/demo/Cycle.java", "rlfixer_unfixable")])

    def test_resource_fields_are_made_final_or_local_before_analysis(self) -> None:
        report, bundle_files, repo_root = self._repair_and_apply(
            "javac-field-transformations", build_command=["./build.sh"], verify=["./build.sh"]
        )

        changes = {(c["file"], c["field"], c["change"]) for c in report["leaks"]["field_changes"]["changes"]}
        self.assertEqual(changes, {
            ("src/demo/TempFileWriter.java", "stream", "final"),
            ("src/demo/Server.java", "socket", "final"),
            ("src/demo/LineCounter.java", "reader", "local"),
        })
        writer = (repo_root / "src/demo/TempFileWriter.java").read_text()
        self.assertIn("private final PrintStream stream;", writer)
        self.assertIn("private String path;", writer, "a field that holds no resource is left alone")
        server = (repo_root / "src/demo/Server.java").read_text()
        self.assertIn("ServerSocket tempSocket = null;", server)
        self.assertIn("this.socket = tempSocket;", server)
        self.assertNotIn("private BufferedReader reader;", (repo_root / "src/demo/LineCounter.java").read_text())
        self.assertEqual(report["leaks"]["summary"]["remaining"], 0)

    def test_latin1_java8_project_is_analyzed_with_the_builds_encoding_and_release(self) -> None:
        temp_root = Path(tempfile.mkdtemp(prefix="arodnap-e2e-"))
        self.addCleanup(shutil.rmtree, temp_root, True)
        repo_root = temp_root / "javac-latin1"
        shutil.copytree(FIXTURES_ROOT / "javac-latin1", repo_root, ignore=shutil.ignore_patterns("out"))
        build = ["--", "./build.sh"]

        out_dir = temp_root / "infer-out"
        self.assertEqual(main(["infer", "--out-dir", str(out_dir), str(repo_root), *build]), 0)
        report = json.loads((out_dir / "report.json").read_text())
        self.assertTrue(report["success"], report.get("error"))
        [run] = report["analysis_runs"]
        metadata = json.loads(Path(run["adapter_metadata_path"]).read_text())
        self.assertEqual((metadata["release"], metadata["encoding"]), (8, "ISO-8859-1"))
        self.assertGreaterEqual(run["warning_count"], 1)

        # The repair tools read sources as UTF-8, so repair stops with a clear message.
        out_dir = temp_root / "repair-out"
        original = _snapshot(repo_root)
        self.assertEqual(main(["repair", "--out-dir", str(out_dir), str(repo_root), *build]), 1)
        report = json.loads((out_dir / "report.json").read_text())
        self.assertFalse(report["success"])
        self.assertIn("is not valid UTF-8", report["error"])
        self.assertEqual(_snapshot(repo_root), original)

    def _repair_and_apply(
        self,
        fixture_name: str,
        *,
        verify: list[str],
        build_command: list[str] | None = None,
    ) -> tuple[dict, list[str], Path]:
        temp_root = Path(tempfile.mkdtemp(prefix="arodnap-e2e-"))
        self.addCleanup(shutil.rmtree, temp_root, True)
        repo_root = temp_root / fixture_name
        shutil.copytree(
            FIXTURES_ROOT / fixture_name,
            repo_root,
            ignore=shutil.ignore_patterns("build", ".gradle", "target", "out"),
        )
        out_dir = temp_root / "arodnap-out"
        original = _snapshot(repo_root)

        command_suffix = ["--", *build_command] if build_command else []
        self.assertEqual(main(["repair", "--out-dir", str(out_dir), str(repo_root), *command_suffix]), 0)

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
            verify,
            cwd=repo_root,
            capture_output=True,
            text=True,
        )
        self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
        return report, entry["changed_files"], repo_root


_GRADLE = ["gradle", "--no-daemon", "--console=plain", "-q", "compileJava"]


def _snapshot(root: Path) -> dict[str, bytes]:
    return {
        path.relative_to(root).as_posix(): path.read_bytes()
        for path in sorted(root.rglob("*.java"))
        if path.is_file()
    }


if __name__ == "__main__":
    unittest.main()
