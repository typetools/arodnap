import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from arodnap.analysis import WpiRunError, run_wpi
from arodnap.analysis.checker_framework import CheckerFrameworkError
from arodnap.analysis.wpi_runner import MAX_WPI_ITERATIONS, WPI_ITERATION_FLAGS
from arodnap.contracts import RunConfig, Timeouts
from arodnap.runtime import CommandResult, Jdk

_JDK = Jdk(home=Path("/jdk-24"), major_version=24, source="JAVA_HOME")


class WpiRunnerTest(unittest.TestCase):
    def setUp(self) -> None:
        jdk_patcher = patch("arodnap.analysis.wpi_runner.resolve_analysis_jdk", return_value=_JDK)
        self.resolve_jdk = jdk_patcher.start()
        self.addCleanup(jdk_patcher.stop)

    def test_iterates_to_a_fixpoint_and_keeps_only_the_final_iteration(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            inputs = self._make_inputs(Path(temp_dir))
            # Iteration 1 infers one annotation, iteration 2 adds another, iteration 3 repeats it.
            outputs = ["@A", "@A @B", "@A @B"]
            commands: list[list[str]] = []

            def fake_run(command, *, cwd, **kwargs):
                commands.append(command)
                generated = Path(cwd) / "build" / "whole-program-inference" / "demo"
                generated.mkdir(parents=True)
                (generated / "Demo-RLC.ajava").write_text(outputs[len(commands) - 1])
                return CommandResult(tuple(command), Path(cwd), 0, "", "")

            with patch("arodnap.analysis.wpi_runner.run_command", side_effect=fake_run):
                result = run_wpi(inputs["config"], **inputs["paths"])

            self.assertEqual(result.iterations, 3)
            self.assertEqual(len(commands), 3)
            self.assertEqual(
                commands[0][:5],
                ["/jdk-24/bin/java", "-jar", str(inputs["cf_root"] / "checker" / "dist" / "checker.jar"),
                 "-processor", "org.checkerframework.checker.resourceleak.ResourceLeakChecker"],
            )
            for command in commands:
                for flag in WPI_ITERATION_FLAGS:
                    self.assertIn(flag, command)
                self.assertEqual(command[command.index("-classpath") + 1], "/deps/a.jar:/out/classes")
                self.assertEqual(command[-1], f"@{inputs['sources'].resolve()}")
            self.assertFalse(any(arg.startswith("-Aajava=") for arg in commands[0]))
            # Each later iteration reads only the previous iteration's output.
            self.assertTrue(any(arg.startswith("-Aajava=") and arg.endswith("iteration1") for arg in commands[1]))
            self.assertTrue(any(arg.startswith("-Aajava=") and arg.endswith("iteration2") for arg in commands[2]))

            inferred = inputs["paths"]["inference_root"]
            self.assertEqual([p.name for p in inferred.iterdir()], ["demo"])
            self.assertEqual((inferred / "demo" / "Demo-RLC.ajava").read_text(), "@A @B")
            self.assertIn("FIXPOINT_AFTER_ITERATIONS: 3", result.log_path.read_text())

    def test_every_iteration_uses_the_builds_release_and_encoding(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            inputs = self._make_inputs(Path(temp_dir))
            commands: list[list[str]] = []

            def fake_run(command, *, cwd, **kwargs):
                commands.append(command)
                return CommandResult(tuple(command), Path(cwd), 0, "", "")

            with patch("arodnap.analysis.wpi_runner.run_command", side_effect=fake_run):
                run_wpi(inputs["config"], **inputs["paths"], release=8, encoding="ISO-8859-1")

            for command in commands:
                self.assertEqual(command[command.index("--release") + 1], "8")
                self.assertEqual(command[command.index("-encoding") + 1], "ISO-8859-1")

    def test_compile_failure_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            inputs = self._make_inputs(Path(temp_dir))
            failed = CommandResult(("java",), None, 1, "", "error: cannot find symbol")

            with patch("arodnap.analysis.wpi_runner.run_command", return_value=failed):
                with self.assertRaisesRegex(WpiRunError, "WPI iteration 1 failed to compile"):
                    run_wpi(inputs["config"], **inputs["paths"])

            self.assertIn("cannot find symbol", inputs["paths"]["log_path"].read_text())

    def test_ajava_files_the_checker_framework_cannot_write_are_reported_not_fatal(self) -> None:
        # Upstream bug: a lone surrogate escape in a comment breaks the .ajava writer.
        with tempfile.TemporaryDirectory() as temp_dir:
            inputs = self._make_inputs(Path(temp_dir))
            failure = "error: Error while writing ajava file build/whole-program-inference/demo/Reader-RLC.ajava\n"

            def fake_run(command, *, cwd, **kwargs):
                generated = Path(cwd) / "build" / "whole-program-inference" / "demo"
                generated.mkdir(parents=True)
                (generated / "Other-RLC.ajava").write_text("@A")
                return CommandResult(tuple(command), Path(cwd), 1, "", failure + "  Exception: MalformedInputException\n1 error\n")

            with patch("arodnap.analysis.wpi_runner.run_command", side_effect=fake_run):
                result = run_wpi(inputs["config"], **inputs["paths"])

            self.assertEqual(result.incomplete, ("build/whole-program-inference/demo/Reader-RLC.ajava",))
            self.assertTrue((inputs["paths"]["inference_root"] / "demo" / "Other-RLC.ajava").is_file())
            self.assertIn("INCOMPLETE_INFERENCE: build/whole-program-inference/demo/Reader-RLC.ajava",
                          result.log_path.read_text())

    def test_real_compile_errors_still_fail_alongside_ajava_failures(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            inputs = self._make_inputs(Path(temp_dir))
            output = (
                "/ws/src/A.java:3: error: cannot find symbol\n"
                "error: Error while writing ajava file build/whole-program-inference/A-RLC.ajava\n"
            )
            failed = CommandResult(("java",), None, 1, "", output)
            with patch("arodnap.analysis.wpi_runner.run_command", return_value=failed):
                with self.assertRaisesRegex(WpiRunError, "failed to compile"):
                    run_wpi(inputs["config"], **inputs["paths"])

    def test_no_fixpoint_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            inputs = self._make_inputs(Path(temp_dir))
            counter = iter(range(1000))

            def fake_run(command, *, cwd, **kwargs):
                generated = Path(cwd) / "build" / "whole-program-inference"
                generated.mkdir(parents=True)
                (generated / "Demo.ajava").write_text(str(next(counter)))
                return CommandResult(tuple(command), Path(cwd), 0, "", "")

            with patch("arodnap.analysis.wpi_runner.run_command", side_effect=fake_run):
                with self.assertRaisesRegex(WpiRunError, f"did not reach a fixpoint after {MAX_WPI_ITERATIONS}"):
                    run_wpi(inputs["config"], **inputs["paths"])

    def test_unsupported_jdk_fails_before_running(self) -> None:
        self.resolve_jdk.side_effect = CheckerFrameworkError("needs JDK 17 or newer; found JDK 11")
        with tempfile.TemporaryDirectory() as temp_dir:
            inputs = self._make_inputs(Path(temp_dir))
            with patch("arodnap.analysis.wpi_runner.run_command") as run_mock:
                with self.assertRaisesRegex(WpiRunError, "needs JDK 17 or newer"):
                    run_wpi(inputs["config"], **inputs["paths"])
            run_mock.assert_not_called()

    def _make_inputs(self, root: Path) -> dict:
        cf_root = root / "cf"
        (cf_root / "checker" / "dist").mkdir(parents=True)
        (cf_root / "checker" / "dist" / "checker.jar").write_bytes(b"jar")
        workspace_root = root / "workspace"
        workspace_root.mkdir()
        sources = root / "sources.txt"
        sources.write_text("/workspace/src/Demo.java\n")
        classpath = root / "classpath.txt"
        classpath.write_text("/deps/a.jar\n/out/classes\n")
        config = RunConfig(
            command="infer",
            repo_root=root / "repo",
            out_dir=root / "out",
            keep_workspace=False,
            workspace_mode="copy",
            build_args=[],
            compile_target=None,
            patch_dir=None,
            cf_root=cf_root,
            close_injector_jar=root / "close.jar",
            owning_field_jar=root / "owning.jar",
            rlfixer_jar=root / "rlfixer.jar",
            rlpatcher_jar=root / "rlpatcher.jar",
            timeouts=Timeouts(),
        )
        return {
            "config": config,
            "cf_root": cf_root,
            "sources": sources,
            "paths": {
                "workspace_root": workspace_root,
                "source_files_file": sources,
                "classpath_entries_file": classpath,
                "log_path": root / "logs" / "wpi.log",
                "inference_root": root / "inference" / "initial",
            },
        }


if __name__ == "__main__":
    unittest.main()
