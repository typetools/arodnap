import tempfile
import unittest
from pathlib import Path

from arodnap.build_adapters import (
    AdapterMetadata,
    BuildToolSelection,
    GradleAdapter,
    UnsupportedProjectError,
    default_build_tool_selection,
    select_build_adapter,
)


FIXTURES_ROOT = Path(__file__).resolve().parent / "fixtures"


class AdapterRegistryTest(unittest.TestCase):
    def test_registry_selects_gradle_for_supported_repo(self) -> None:
        adapter = select_build_adapter(FIXTURES_ROOT / "gradle-source-file-filtering")

        self.assertIsInstance(adapter, GradleAdapter)
        self.assertEqual(
            adapter.detect(),
            BuildToolSelection(build_system="gradle", adapter_name="gradle-v1"),
        )

    def test_registry_preserves_unsupported_repo_failure_for_missing_gradle_shape(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir) / "repo"
            repo_root.mkdir()

            with self.assertRaisesRegex(
                UnsupportedProjectError,
                "Gradle repo root must contain one of",
            ):
                select_build_adapter(repo_root)

    def test_default_selection_matches_registered_gradle_backend(self) -> None:
        self.assertEqual(
            default_build_tool_selection(),
            BuildToolSelection(build_system="gradle", adapter_name="gradle-v1"),
        )

    def test_adapter_metadata_payload_serializes_lightweight_shared_fields(self) -> None:
        metadata = AdapterMetadata(
            repo_root=Path("/repo"),
            build_file=Path("/repo/build.gradle"),
            build_system="gradle",
            adapter_name="gradle-v1",
            build_tool=("./gradlew",),
            build_tool_source="wrapper",
            compile_target="classes",
            source_root=Path("/repo/src/main/java"),
            compiled_classes_root=Path("/repo/build/classes/java/main"),
            source_files_file=Path("/out/source-files.txt"),
            app_classes_file=Path("/out/app-classes.txt"),
            classpath_entries_file=Path("/out/classpath.txt"),
        )

        self.assertEqual(
            metadata.to_payload(),
            {
                "repo_root": "/repo",
                "build_file": "/repo/build.gradle",
                "build_system": "gradle",
                "adapter_name": "gradle-v1",
                "build_tool": ["./gradlew"],
                "build_tool_source": "wrapper",
                "compile_target": "classes",
                "source_root": "/repo/src/main/java",
                "compiled_classes_root": "/repo/build/classes/java/main",
                "source_files_file": "/out/source-files.txt",
                "app_classes_file": "/out/app-classes.txt",
                "classpath_entries_file": "/out/classpath.txt",
            },
        )


if __name__ == "__main__":
    unittest.main()
