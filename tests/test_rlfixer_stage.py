import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from arodnap.contracts import RunConfig, StageResult, Timeouts
from arodnap.runtime import CommandResult
from arodnap.stages.rlfixer import StageExecutionError, run_rlfixer_stage

_LEAK_WARNING = (
    "{path}:10: warning: (required.method.not.called) $$ 4 $$ method close $$ stream $$ "
    "java.io.FileInputStream $$ possible exceptional exit $$ ( 1, 2 ) $$ @MustCall method close may not "
    "have been invoked on stream or any of its aliases.\n"
    "            FileInputStream stream = new FileInputStream(path);\n"
    "                            ^\n"
)
_OWNING_WARNING = (
    "{path}:16: warning: (required.method.not.called) $$ 4 $$ method close $$ field current $$ "
    "java.io.InputStream $$  Non-final owning field might be overwritten $$ ( 3, 4 ) $$ ...\n"
)
_FIXES_OUTPUT = """5701
196

SOURCE LEVEL FIXES

0] {path}; Line number 10
vim +10 {path}

+++ Add following code below line: 14 (com/example/Demo.java)
finally{{ }}
--------------------------------------------
"""


class RLFixerStageTest(unittest.TestCase):
    def setUp(self) -> None:
        java_patcher = patch("arodnap.stages.rlfixer.java_executable", return_value="java")
        java_patcher.start()
        self.addCleanup(java_patcher.stop)

    def test_invokes_rlfixer_with_build_discovered_inputs(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            inputs = self._make_inputs(Path(temp_dir))
            source = inputs["source_file"]
            inputs["diagnostics_path"].write_text(
                _LEAK_WARNING.format(path=source) + _OWNING_WARNING.format(path=source) + "2 warnings\n"
            )
            captured: dict[str, list[str]] = {}

            def fake_run_stage_command(*, command: list[str], cwd: Path) -> CommandResult:
                captured["command"] = command
                Path(command[command.index("-debugOutput") + 1]).write_text("Index^Source File\n")
                return CommandResult(
                    command=tuple(command),
                    cwd=cwd,
                    returncode=0,
                    stdout=_FIXES_OUTPUT.format(path=source),
                    stderr="",
                )

            with patch("arodnap.stages.rlfixer.run_stage_command", side_effect=fake_run_stage_command):
                result = self._run(inputs)

            command = captured["command"]
            stage_dir = inputs["stage_output_dir"].resolve()
            self.assertEqual(command[:3], ["java", "-jar", str(inputs["config"].rlfixer_jar.resolve())])
            self.assertEqual(
                command[command.index("-warnings") + 1],
                "com/example/Demo.java,10,None,False#com/example/Demo.java,16,None,True#",
            )
            self.assertEqual(command[command.index("-projectDir") + 1], str(inputs["source_root"].resolve()))
            self.assertEqual(
                command[command.index("-classpath") + 1],
                os.pathsep.join([str(inputs["classes_dir"]), str(inputs["dependency_jar"])]),
            )
            self.assertEqual(
                (stage_dir / "inputs" / "sources.txt").read_text(),
                "com/example/Demo.java\n",
            )
            self.assertEqual((stage_dir / "fixes.txt").read_text(), _FIXES_OUTPUT.format(path=source))
            self.assertEqual(
                result,
                StageResult(
                    stage="rlfixer",
                    changed=False,
                    changed_files=[],
                    rerun_required=False,
                    artifacts={
                        "log": str(stage_dir / "stage.log"),
                        "fixes": str(stage_dir / "fixes.txt"),
                        "debug": str(stage_dir / "debug.txt"),
                        "inputs": str(stage_dir / "inputs"),
                    },
                    notes=["RLFixer proposed 1 fix(es) for 2 leak warning(s)."],
                    success=True,
                ),
            )
            self.assertEqual(json.loads((stage_dir / "stage_result.json").read_text()), result.to_dict())

    def test_no_leak_warnings_skips_rlfixer(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            inputs = self._make_inputs(Path(temp_dir))
            inputs["diagnostics_path"].write_text("0 warnings\n")

            with patch("arodnap.stages.rlfixer.run_stage_command") as run_mock:
                result = self._run(inputs)

            run_mock.assert_not_called()
            stage_dir = inputs["stage_output_dir"].resolve()
            self.assertEqual((stage_dir / "fixes.txt").read_text(), "")
            self.assertEqual((stage_dir / "debug.txt").read_text(), "")
            self.assertEqual(result.notes, ["No resource leak warnings to repair."])

    def test_nonzero_exit_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            inputs = self._make_inputs(Path(temp_dir))
            inputs["diagnostics_path"].write_text(_LEAK_WARNING.format(path=inputs["source_file"]))
            completed = CommandResult(command=("java",), cwd=None, returncode=1, stdout="", stderr="boom")

            with patch("arodnap.stages.rlfixer.run_stage_command", return_value=completed):
                with self.assertRaisesRegex(StageExecutionError, "RLFixer failed"):
                    self._run(inputs)

            self.assertIn("boom", (inputs["stage_output_dir"] / "stage.log").read_text())

    def test_missing_fixes_report_fails_closed(self) -> None:
        # The legacy runner reported success even when RLFixer crashed; a zero exit
        # without the fixes report must not be treated as "no fixes".
        with tempfile.TemporaryDirectory() as temp_dir:
            inputs = self._make_inputs(Path(temp_dir))
            inputs["diagnostics_path"].write_text(_LEAK_WARNING.format(path=inputs["source_file"]))
            completed = CommandResult(command=("java",), cwd=None, returncode=0, stdout="", stderr="")

            with patch("arodnap.stages.rlfixer.run_stage_command", return_value=completed):
                with self.assertRaisesRegex(StageExecutionError, "did not produce a fixes report"):
                    self._run(inputs)

    def test_warning_outside_source_root_is_skipped_with_note(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            inputs = self._make_inputs(temp_root)
            generated = temp_root / "workspace" / "build" / "generated" / "Gen.java"
            inputs["diagnostics_path"].write_text(_LEAK_WARNING.format(path=generated))

            with patch("arodnap.stages.rlfixer.run_stage_command") as run_mock:
                result = self._run(inputs)

            run_mock.assert_not_called()
            self.assertIn("Skipped leak warning outside source root", result.notes[0])

    def test_missing_inference_dir_fails_before_running(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            inputs = self._make_inputs(Path(temp_dir))
            inputs["inference_dir"].rmdir()

            with patch("arodnap.stages.rlfixer.run_stage_command") as run_mock:
                with self.assertRaisesRegex(StageExecutionError, "Missing inference directory"):
                    self._run(inputs)
            run_mock.assert_not_called()

    def _run(self, inputs: dict) -> StageResult:
        return run_rlfixer_stage(
            config=inputs["config"],
            workspace_root=inputs["workspace_root"],
            diagnostics_path=inputs["diagnostics_path"],
            inference_dir=inputs["inference_dir"],
            source_root=inputs["source_root"],
            source_files_file=inputs["source_files_file"],
            app_classes_file=inputs["app_classes_file"],
            classpath_entries_file=inputs["classpath_entries_file"],
            stage_output_dir=inputs["stage_output_dir"],
        )

    def _make_inputs(self, root: Path) -> dict:
        root = root.resolve()
        workspace_root = root / "workspace"
        source_root = workspace_root / "src" / "main" / "java"
        source_file = source_root / "com" / "example" / "Demo.java"
        source_file.parent.mkdir(parents=True)
        source_file.write_text("package com.example;\nclass Demo {}\n")
        classes_dir = workspace_root / "build" / "classes" / "java" / "main"
        classes_dir.mkdir(parents=True)
        dependency_jar = root / "deps" / "lib.jar"
        dependency_jar.parent.mkdir()
        dependency_jar.write_bytes(b"jar")
        inference_dir = root / "inference"
        inference_dir.mkdir()
        metadata_dir = root / "metadata"
        metadata_dir.mkdir()
        source_files_file = metadata_dir / "source-files.txt"
        source_files_file.write_text(f"{source_file}\n")
        app_classes_file = metadata_dir / "app-classes.txt"
        app_classes_file.write_text("com.example.Demo\n")
        classpath_entries_file = metadata_dir / "classpath-entries.txt"
        classpath_entries_file.write_text(f"{classes_dir}\n{dependency_jar}\n")
        rlfixer_jar = root / "RLFixer.jar"
        rlfixer_jar.write_bytes(b"jar")
        config = RunConfig(
            command="repair",
            repo_root=root / "repo",
            out_dir=root / "arodnap-out",
            keep_workspace=False,
            workspace_mode="copy",
            build_args=[],
            compile_target=None,
            patch_dir=None,
            cf_root=root / "cf",
            close_injector_jar=root / "close.jar",
            owning_field_jar=root / "owning.jar",
            rlfixer_jar=rlfixer_jar,
            rlpatcher_jar=root / "rlpatcher.jar",
            timeouts=Timeouts(build_seconds=1, analysis_seconds=1, stage_seconds=1),
        )
        return {
            "config": config,
            "workspace_root": workspace_root,
            "source_root": source_root,
            "source_file": source_file,
            "classes_dir": classes_dir,
            "dependency_jar": dependency_jar,
            "diagnostics_path": root / "diagnostics.txt",
            "inference_dir": inference_dir,
            "source_files_file": source_files_file,
            "app_classes_file": app_classes_file,
            "classpath_entries_file": classpath_entries_file,
            "stage_output_dir": root / "arodnap-out" / "stages" / "rlfixer",
        }


if __name__ == "__main__":
    unittest.main()
