import tempfile
import unittest
from pathlib import Path

from arodnap.build_adapters import (
    AntCaptureAdapter,
    BuildToolSelection,
    CommandCaptureAdapter,
    GradleCaptureAdapter,
    MavenCaptureAdapter,
    UnsupportedProjectError,
    default_build_tool_selection,
    select_build_adapter,
)

FIXTURES_ROOT = Path(__file__).resolve().parent / "fixtures"


class AdapterRegistryTest(unittest.TestCase):
    def test_build_files_select_the_adapter(self) -> None:
        for fixture, adapter_type in (
            ("gradle-pipeline-baseline", GradleCaptureAdapter),
            ("gradle-multimodule", GradleCaptureAdapter),
            ("maven-dependency-leak", MavenCaptureAdapter),
            ("ant-vendored-jar", AntCaptureAdapter),
        ):
            with self.subTest(fixture=fixture):
                self.assertIsInstance(select_build_adapter(FIXTURES_ROOT / fixture), adapter_type)

    def test_build_command_executable_selects_the_adapter(self) -> None:
        repo_root = FIXTURES_ROOT / "javac-script"
        for command, adapter_type in (
            (["./gradlew", "build"], GradleCaptureAdapter),
            (["mvn", "-Pci", "compile"], MavenCaptureAdapter),
            (["/usr/local/bin/ant", "jar"], AntCaptureAdapter),
            (["./build.sh"], CommandCaptureAdapter),
            (["make", "all"], CommandCaptureAdapter),
        ):
            with self.subTest(command=command):
                adapter = select_build_adapter(repo_root, build_command=command)
                self.assertIsInstance(adapter, adapter_type)
                self.assertEqual(adapter.build_command, tuple(command))

    def test_repo_without_a_known_build_file_needs_a_command(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            with self.assertRaisesRegex(UnsupportedProjectError, "pass the build command after --"):
                select_build_adapter(Path(temp_dir))

    def test_default_selection_is_gradle(self) -> None:
        self.assertEqual(
            default_build_tool_selection(),
            BuildToolSelection(build_system="gradle", adapter_name="gradle"),
        )


class CaptureCommandTest(unittest.TestCase):
    def test_hooks_are_added_to_default_and_user_commands(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            capture_dir = Path(temp_dir)
            shim = capture_dir / "bin" / "javac"
            repo = FIXTURES_ROOT / "gradle-pipeline-baseline"

            gradle = GradleCaptureAdapter(repo, build_command=["gradle", "assemble"]).capture_command(
                capture_dir=capture_dir, shim=shim
            )
            self.assertEqual(gradle[0], "gradle")
            self.assertIn("-I", gradle)
            self.assertIn("--no-build-cache", gradle)
            self.assertEqual(gradle[-1], "assemble")

            maven = MavenCaptureAdapter(repo, build_command=["mvn", "compile"]).capture_command(
                capture_dir=capture_dir, shim=shim
            )
            self.assertEqual(
                maven,
                ("mvn", "compile", "-Dmaven.compiler.fork=true", f"-Dmaven.compiler.executable={shim}"),
            )

            ant = AntCaptureAdapter(repo, build_command=["ant", "jar"]).capture_command(
                capture_dir=capture_dir, shim=shim
            )
            self.assertEqual(ant[0], "ant")
            self.assertIn("-Dbuild.compiler=org.arodnap.capture.RecordingJavacAdapter", ant)
            self.assertEqual(ant[-1], "jar")

            command = CommandCaptureAdapter(repo, build_command=["./build.sh"]).capture_command(
                capture_dir=capture_dir, shim=shim
            )
            self.assertEqual(command, ("./build.sh",))


if __name__ == "__main__":
    unittest.main()
