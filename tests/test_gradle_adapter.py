import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from arodnap.build_adapters import (
    AdapterExecutionError,
    BuildAdapterContract,
    BuildToolSelection,
    GradleAdapter,
    MissingBuildToolError,
    UnsupportedProjectError,
)
from arodnap.runtime import CommandExecutionError, CommandResult


FIXTURES_ROOT = Path(__file__).resolve().parent / "fixtures"


class GradleAdapterTest(unittest.TestCase):
    def test_gradle_adapter_implements_shared_contract(self) -> None:
        adapter = GradleAdapter(FIXTURES_ROOT / "gradle-source-file-filtering")

        self.assertIsInstance(adapter, BuildAdapterContract)
        self.assertEqual(
            adapter.detect(),
            BuildToolSelection(build_system="gradle", adapter_name="gradle-v1"),
        )

    def test_supported_fixture_is_classified_supported(self) -> None:
        project = GradleAdapter(FIXTURES_ROOT / "gradle-pipeline-baseline").inspect()

        self.assertEqual(project.repo_root, (FIXTURES_ROOT / "gradle-pipeline-baseline").resolve())
        self.assertEqual(project.build_file.name, "build.gradle")
        self.assertEqual(project.build_system, "gradle")
        self.assertEqual(project.adapter_name, "gradle-v1")
        self.assertEqual(project.build_tool, ("gradle",))
        self.assertEqual(project.build_tool_source, "system")
        self.assertEqual(project.compile_target, "classes")
        self.assertEqual(project.source_root, project.repo_root / "src" / "main" / "java")
        self.assertEqual(
            project.compiled_classes_root,
            project.repo_root / "build" / "classes" / "java" / "main",
        )

    def test_multimodule_fixture_is_rejected(self) -> None:
        with self.assertRaisesRegex(UnsupportedProjectError, "Multi-module"):
            GradleAdapter(FIXTURES_ROOT / "gradle-multimodule-unsupported").inspect()

    def test_nonstandard_layout_fixture_is_rejected(self) -> None:
        with self.assertRaisesRegex(UnsupportedProjectError, "src/main/java"):
            GradleAdapter(FIXTURES_ROOT / "gradle-nonstandard-layout-unsupported").inspect()

    def test_prefers_wrapper_when_present_and_executable(self) -> None:
        repo_root = self._make_minimal_repo(with_wrapper=True)

        with patch.object(GradleAdapter, "validate_compile", return_value=None):
            project = GradleAdapter(repo_root).inspect()

        self.assertEqual(project.build_tool, ("./gradlew",))

    def test_falls_back_to_gradle_when_wrapper_is_absent(self) -> None:
        repo_root = self._make_minimal_repo(with_wrapper=False)

        with patch.object(GradleAdapter, "validate_compile", return_value=None):
            with patch("shutil.which", return_value="/usr/bin/gradle"):
                project = GradleAdapter(repo_root).inspect()

        self.assertEqual(project.build_tool, ("gradle",))

    def test_missing_build_tool_is_rejected(self) -> None:
        repo_root = self._make_minimal_repo(with_wrapper=False)

        with patch("shutil.which", return_value=None):
            with self.assertRaises(MissingBuildToolError):
                with patch.object(GradleAdapter, "validate_compile", return_value=None):
                    GradleAdapter(repo_root).inspect()

    def test_compile_validation_forwards_build_args(self) -> None:
        repo_root = self._make_minimal_repo(with_wrapper=False)
        with patch("shutil.which", return_value="/usr/bin/gradle"):
            adapter = GradleAdapter(repo_root, build_args=["--info", "-x=test"])
            project = adapter.inspect()
            completed = CommandResult(command=(), cwd=repo_root.resolve(), returncode=0, stdout="", stderr="")
            with patch(
                "arodnap.build_adapters.gradle.run_command",
                side_effect=self._make_run_command_side_effect(completed),
            ) as run_mock:
                adapter.validate_compile(project)

        command = run_mock.call_args.args[0]
        self.assertEqual(
            command,
            ["gradle", "--no-daemon", "--console=plain", "--info", "-x=test", "classes"],
        )
        self.assertIn("GRADLE_USER_HOME", run_mock.call_args.kwargs["env"])

    def test_supported_fixture_writes_expected_source_file_list(self) -> None:
        adapter = GradleAdapter(FIXTURES_ROOT / "gradle-pipeline-baseline")
        project = adapter.inspect()

        with tempfile.TemporaryDirectory() as temp_dir:
            output_path = Path(temp_dir) / "sources.txt"
            written_path = adapter.write_source_files_file(project, output_path)

            self.assertEqual(written_path, output_path)
            self.assertEqual(
                written_path.read_text().splitlines(),
                [
                    str(
                        (
                            FIXTURES_ROOT
                            / "gradle-pipeline-baseline"
                            / "src/main/java/com/arodnap/fixture/BaselineSmoke.java"
                        ).resolve()
                    ),
                    str(
                        (
                            FIXTURES_ROOT
                            / "gradle-pipeline-baseline"
                            / "src/main/java/com/arodnap/fixture/DirectLeakExample.java"
                        ).resolve()
                    ),
                    str(
                        (
                            FIXTURES_ROOT
                            / "gradle-pipeline-baseline"
                            / "src/main/java/com/arodnap/fixture/OwningFieldReassignment.java"
                        ).resolve()
                    ),
                    str(
                        (
                            FIXTURES_ROOT
                            / "gradle-pipeline-baseline"
                            / "src/main/java/com/arodnap/fixture/TryCatchLeakExample.java"
                        ).resolve()
                    ),
                    str(
                        (
                            FIXTURES_ROOT
                            / "gradle-pipeline-baseline"
                            / "src/main/java/com/arodnap/fixture/WrapperMissingClose.java"
                        ).resolve()
                    ),
                ],
            )

    def test_source_file_list_excludes_tests_and_generated_sources(self) -> None:
        adapter = GradleAdapter(FIXTURES_ROOT / "gradle-source-file-filtering")

        with patch.object(GradleAdapter, "validate_compile", return_value=None):
            project = adapter.inspect()

        with tempfile.TemporaryDirectory() as temp_dir:
            output_path = Path(temp_dir) / "sources.txt"
            adapter.write_source_files_file(project, output_path)
            source_files = output_path.read_text().splitlines()

        self.assertEqual(
            source_files,
            [
                str(
                    (
                        FIXTURES_ROOT
                        / "gradle-source-file-filtering"
                        / "src/main/java/com/arodnap/fixture/MainApp.java"
                    ).resolve()
                )
            ],
        )

    def test_supported_fixture_writes_expected_app_class_list(self) -> None:
        adapter = GradleAdapter(FIXTURES_ROOT / "gradle-pipeline-baseline")
        project = adapter.inspect()

        with tempfile.TemporaryDirectory() as temp_dir:
            output_path = Path(temp_dir) / "app-classes.txt"
            written_path = adapter.write_app_classes_file(project, output_path)

            self.assertEqual(written_path, output_path)
            self.assertEqual(
                written_path.read_text().splitlines(),
                [
                    "com.arodnap.fixture.BaselineSmoke",
                    "com.arodnap.fixture.DirectLeakExample",
                    "com.arodnap.fixture.OwningFieldReassignment",
                    "com.arodnap.fixture.TryCatchLeakExample",
                    "com.arodnap.fixture.WrapperMissingClose",
                ],
            )

    def test_app_class_list_preserves_nested_classes_and_excludes_module_info(self) -> None:
        adapter = GradleAdapter(FIXTURES_ROOT / "gradle-app-classes")
        project = adapter.inspect()

        with tempfile.TemporaryDirectory() as temp_dir:
            output_path = Path(temp_dir) / "app-classes.txt"
            adapter.write_app_classes_file(project, output_path)
            class_names = output_path.read_text().splitlines()

        self.assertEqual(
            class_names,
            [
                "com.arodnap.fixture.Helper",
                "com.arodnap.fixture.Outer",
                "com.arodnap.fixture.Outer$Nested",
            ],
        )

    def test_supported_fixture_writes_expected_classpath_entries(self) -> None:
        adapter = GradleAdapter(FIXTURES_ROOT / "gradle-pipeline-baseline")
        project = adapter.inspect()

        with tempfile.TemporaryDirectory() as temp_dir:
            output_path = Path(temp_dir) / "classpath.txt"
            written_path = adapter.write_classpath_entries_file(project, output_path)
            entries = written_path.read_text().splitlines()

        self.assertEqual(written_path, output_path)
        self.assertTrue(entries)
        self.assertEqual(entries, sorted(set(entries)))
        self.assertTrue(all(Path(entry).is_absolute() for entry in entries))
        self.assertIn(str(project.compiled_classes_root.resolve()), entries)

    def test_supported_fixture_writes_adapter_metadata(self) -> None:
        adapter = GradleAdapter(FIXTURES_ROOT / "gradle-pipeline-baseline")
        project = adapter.inspect()

        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            source_files_file = adapter.write_source_files_file(project, temp_root / "sources.txt")
            app_classes_file = adapter.write_app_classes_file(project, temp_root / "classes.txt")
            classpath_entries_file = adapter.write_classpath_entries_file(project, temp_root / "classpath.txt")
            metadata_path = adapter.write_adapter_metadata_file(
                project,
                source_files_file=source_files_file,
                app_classes_file=app_classes_file,
                classpath_entries_file=classpath_entries_file,
                output_path=temp_root / "adapter.json",
            )
            metadata = json.loads(metadata_path.read_text())

        self.assertEqual(metadata_path, temp_root / "adapter.json")
        self.assertEqual(metadata["repo_root"], str(project.repo_root))
        self.assertEqual(metadata["build_file"], str(project.build_file))
        self.assertEqual(metadata["build_system"], "gradle")
        self.assertEqual(metadata["adapter_name"], "gradle-v1")
        self.assertEqual(metadata["build_tool"], list(project.build_tool))
        self.assertEqual(metadata["build_tool_source"], "system")
        self.assertEqual(metadata["compile_target"], project.compile_target)
        self.assertEqual(metadata["source_root"], str(project.source_root))
        self.assertEqual(metadata["compiled_classes_root"], str(project.compiled_classes_root))
        self.assertEqual(metadata["source_files_file"], str(source_files_file))
        self.assertEqual(metadata["app_classes_file"], str(app_classes_file))
        self.assertEqual(metadata["classpath_entries_file"], str(classpath_entries_file))

    def test_wrapper_selection_is_reported_in_project_model_and_metadata(self) -> None:
        repo_root = self._make_minimal_repo(with_wrapper=True)

        with patch.object(GradleAdapter, "validate_compile", return_value=None):
            project = GradleAdapter(repo_root).inspect()

        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            source_files_file = temp_root / "sources.txt"
            app_classes_file = temp_root / "classes.txt"
            classpath_entries_file = temp_root / "classpath.txt"
            source_files_file.write_text("")
            app_classes_file.write_text("")
            classpath_entries_file.write_text(str(project.compiled_classes_root.resolve()) + "\n")
            metadata_path = GradleAdapter(repo_root).write_adapter_metadata_file(
                project,
                source_files_file=source_files_file,
                app_classes_file=app_classes_file,
                classpath_entries_file=classpath_entries_file,
                output_path=temp_root / "adapter.json",
            )
            metadata = json.loads(metadata_path.read_text())

        self.assertEqual(project.build_tool, ("./gradlew",))
        self.assertEqual(project.build_tool_source, "wrapper")
        self.assertEqual(metadata["build_tool"], ["./gradlew"])
        self.assertEqual(metadata["build_tool_source"], "wrapper")

    def test_classpath_extraction_uses_init_script_task_flow(self) -> None:
        adapter = GradleAdapter(FIXTURES_ROOT / "gradle-source-file-filtering")

        with patch.object(GradleAdapter, "validate_compile", return_value=None):
            project = adapter.inspect()

        completed = CommandResult(
            command=(),
            cwd=project.repo_root.resolve(),
            returncode=0,
            stdout=f"{project.compiled_classes_root.resolve()}\n",
            stderr="",
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            output_path = Path(temp_dir) / "classpath.txt"
            with patch(
                "arodnap.build_adapters.gradle.run_command",
                side_effect=self._make_run_command_side_effect(completed),
            ) as run_mock:
                adapter.write_classpath_entries_file(project, output_path)
                written_entries = output_path.read_text().splitlines()

        command = run_mock.call_args.args[0]
        self.assertIn("-I", command)
        self.assertIn("arodnapPrintMainClasspath", command)
        self.assertEqual(written_entries, [str(project.compiled_classes_root.resolve())])

    def test_classpath_extraction_forwards_build_args(self) -> None:
        adapter = GradleAdapter(FIXTURES_ROOT / "gradle-source-file-filtering", build_args=["--info", "-x=test"])

        with patch.object(GradleAdapter, "validate_compile", return_value=None):
            project = adapter.inspect()

        completed = CommandResult(
            command=(),
            cwd=project.repo_root.resolve(),
            returncode=0,
            stdout=f"{project.compiled_classes_root.resolve()}\n",
            stderr="",
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            output_path = Path(temp_dir) / "classpath.txt"
            with patch(
                "arodnap.build_adapters.gradle.run_command",
                side_effect=self._make_run_command_side_effect(completed),
            ) as run_mock:
                adapter.write_classpath_entries_file(project, output_path)

        command = run_mock.call_args.args[0]
        self.assertEqual(command[:7], ["gradle", "--no-daemon", "--console=plain", "--info", "-x=test", "-q", "-I"])

    def test_classpath_extraction_rejects_empty_output(self) -> None:
        adapter = GradleAdapter(FIXTURES_ROOT / "gradle-source-file-filtering")

        with patch.object(GradleAdapter, "validate_compile", return_value=None):
            project = adapter.inspect()

        completed = CommandResult(command=(), cwd=project.repo_root.resolve(), returncode=0, stdout="", stderr="")
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch("arodnap.build_adapters.gradle.run_command", return_value=completed):
                with self.assertRaisesRegex(AdapterExecutionError, "produced no entries"):
                    adapter.write_classpath_entries_file(project, Path(temp_dir) / "classpath.txt")

    def test_classpath_extraction_rejects_gradle_task_failure(self) -> None:
        adapter = GradleAdapter(FIXTURES_ROOT / "gradle-source-file-filtering")

        with patch.object(GradleAdapter, "validate_compile", return_value=None):
            project = adapter.inspect()

        completed = CommandResult(command=(), cwd=project.repo_root.resolve(), returncode=1, stdout="", stderr="boom")
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch("arodnap.build_adapters.gradle.run_command", return_value=completed):
                with self.assertRaisesRegex(AdapterExecutionError, "classpath extraction failed"):
                    adapter.write_classpath_entries_file(project, Path(temp_dir) / "classpath.txt")

    def test_gradle_command_start_failure_is_wrapped(self) -> None:
        with patch("shutil.which", return_value="/usr/bin/gradle"):
            repo_root = self._make_minimal_repo(with_wrapper=False)
            adapter = GradleAdapter(repo_root)
            project = adapter.inspect()
            with patch(
                "arodnap.build_adapters.gradle.run_command",
                side_effect=CommandExecutionError(
                    command=("gradle", "classes"),
                    cwd=repo_root.resolve(),
                    cause=OSError("boom"),
                ),
            ):
                with self.assertRaisesRegex(AdapterExecutionError, "Failed to execute command"):
                    adapter.validate_compile(project)

    def _make_minimal_repo(self, *, with_wrapper: bool) -> Path:
        temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(temp_dir.cleanup)
        repo_root = Path(temp_dir.name) / "repo"
        (repo_root / "src" / "main" / "java").mkdir(parents=True)
        (repo_root / "src" / "main" / "java" / "Main.java").write_text("class Main {}\n")
        (repo_root / "build.gradle").write_text("plugins { id 'java' }\n")
        if with_wrapper:
            wrapper = repo_root / "gradlew"
            wrapper.write_text("#!/bin/sh\nexit 0\n")
            wrapper.chmod(0o755)
        return repo_root

    def _make_run_command_side_effect(self, template: CommandResult):
        def fake_run(command: list[str], **kwargs) -> CommandResult:
            return CommandResult(
                command=tuple(command),
                cwd=kwargs.get("cwd"),
                returncode=template.returncode,
                stdout=template.stdout,
                stderr=template.stderr,
            )

        return fake_run
