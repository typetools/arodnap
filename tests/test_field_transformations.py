import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from arodnap.contracts import RunConfig, Timeouts
from arodnap.orchestrator.config import resolve_cf_root
from arodnap.runtime import CommandResult
from arodnap.stages.base import CompileInputs, StageExecutionError
from arodnap.stages.field_transformations import EXTRA_RESOURCE_TYPES, run_field_transformations_stage


class ExtraResourceTypesTest(unittest.TestCase):
    def test_list_matches_the_bundled_checker_frameworks_annotated_jdk(self) -> None:
        """Types the annotated JDK marks @MustCall/@InheritableMustCall without implementing
        (Auto)Closeable must be passed to the field transformations."""
        jar = resolve_cf_root() / "checker" / "dist" / "checker.jar"
        if not jar.is_file():
            self.skipTest(f"{jar} is not available")
        found = set()
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if not (name.startswith("annotated-jdk/src/java.") and name.endswith(".java")):
                    continue
                text = archive.read(name).decode("utf-8", errors="replace")
                import re
                match = re.search(r"@InheritableMustCall\(\{?\"(\w+)\"", text)
                if match and not re.search(r"(implements|extends)[^{]*\b(Auto)?Closeable\b", text):
                    qualified = name.split("/classes/", 1)[1][:-len(".java")].replace("/", ".")
                    found.add(f"{qualified}:{match.group(1)}")
        public = {entry for entry in found if not entry.startswith(("sun.", "jdk.internal."))}
        self.assertEqual(public, set(EXTRA_RESOURCE_TYPES))


class FieldTransformationsStageTest(unittest.TestCase):
    def test_edits_in_files_that_stop_compiling_are_undone(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir).resolve()
            workspace = root / "ws"
            (workspace / "src").mkdir(parents=True)
            good, bad = workspace / "src/Good.java", workspace / "src/Bad.java"
            good.write_text("class Good { private java.io.InputStream in; }\n")
            bad.write_text("class Bad { private java.io.InputStream in; }\n")
            sources = root / "sources.txt"
            sources.write_text(f"{good}\n{bad}\n")
            classpath = root / "classpath.txt"
            classpath.write_text("")
            calls = []

            def fake_run(*, command, cwd, timeout_seconds=None):
                calls.append(command)
                if "-Xep:ResourceFieldCanBeFinal" in " ".join(command):
                    # Error Prone edits both files in place and reports each field.
                    good.write_text("class Good { private final java.io.InputStream in; }\n")
                    bad.write_text("class Bad { private final java.io.InputStream in; }\n")
                    notes = (f"{good}:1: Note: [ResourceFieldCanBeFinal] Resource field `in` can be final\n"
                             f"{bad}:1: Note: [ResourceFieldCanBeFinal] Resource field `in` can be final\n")
                    return CommandResult(tuple(command), cwd, 0, "", notes)
                if "-Xep:" in " ".join(command):
                    return CommandResult(tuple(command), cwd, 0, "", "")
                broken = "final" in bad.read_text()
                return CommandResult(tuple(command), cwd, 1 if broken else 0, "", f"{bad}:1: error: boom\n" if broken else "")

            with patch("arodnap.stages.field_transformations.run_stage_command", side_effect=fake_run), \
                    patch("arodnap.stages.field_transformations.PLUGIN_JAR", root / "sources.txt"), \
                    patch("arodnap.stages.field_transformations.ERROR_PRONE_JARS", (root / "sources.txt",)):
                result = run_field_transformations_stage(
                    _config(root), workspace_root=workspace, stage_output_dir=root / "stage",
                    compile_inputs=CompileInputs(sources_file=sources, classpath_file=classpath),
                )

            self.assertEqual(result.changed_files, ["src/Good.java"])
            self.assertIn("private final", good.read_text())
            self.assertNotIn("final", bad.read_text())
            changes = json.loads((root / "stage" / "field_changes.json").read_text())["changes"]
            self.assertEqual({(c["file"], c["kept"]) for c in changes}, {("src/Good.java", True), ("src/Bad.java", False)})
            self.assertIn("Undid the changes to 1 file(s)", " ".join(result.notes))

    def test_errors_outside_edited_files_fail_the_stage(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir).resolve()
            (root / "A.java").write_text("class A {}\n")
            sources = root / "sources.txt"
            sources.write_text(f"{root / 'A.java'}\n")
            (root / "cp.txt").write_text("")

            def fake_run(*, command, cwd, timeout_seconds=None):
                if "-Xep:" in " ".join(command):
                    return CommandResult(tuple(command), cwd, 0, "", "")
                return CommandResult(tuple(command), cwd, 1, "", f"{root / 'Other.java'}:1: error: boom\n")

            with patch("arodnap.stages.field_transformations.run_stage_command", side_effect=fake_run), \
                    patch("arodnap.stages.field_transformations.PLUGIN_JAR", sources), \
                    patch("arodnap.stages.field_transformations.ERROR_PRONE_JARS", (sources,)):
                with self.assertRaisesRegex(StageExecutionError, "no longer compiles"):
                    run_field_transformations_stage(
                        _config(root), workspace_root=root, stage_output_dir=root / "stage",
                        compile_inputs=CompileInputs(sources_file=sources, classpath_file=root / "cp.txt"),
                    )

    def test_off_changes_nothing(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir).resolve()
            with patch("arodnap.stages.field_transformations.run_stage_command") as run_mock:
                result = run_field_transformations_stage(
                    _config(root, mode="off"), workspace_root=root, stage_output_dir=root / "stage",
                    compile_inputs=CompileInputs(sources_file=root / "s", classpath_file=root / "c"),
                )
            run_mock.assert_not_called()
            self.assertFalse(result.changed)


def _config(root: Path, mode: str = "resources") -> RunConfig:
    return RunConfig(
        command="repair", repo_root=root, out_dir=root / "out", keep_workspace=False, workspace_mode="copy",
        build_args=[], compile_target=None, patch_dir=None, cf_root=root, close_injector_jar=root,
        owning_field_jar=root, rlfixer_jar=root, rlpatcher_jar=root, timeouts=Timeouts(),
        field_transformations=mode,
    )


if __name__ == "__main__":
    unittest.main()
