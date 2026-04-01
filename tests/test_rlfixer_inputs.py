import json
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

from arodnap.compat.rlfixer_inputs import (
    RLFixerCompatibilityBundleError,
    generate_rlfixer_compatibility_bundle,
)


class RLFixerCompatibilityBundleTest(unittest.TestCase):
    def test_generate_bundle_writes_expected_info_files_and_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            workspace_root, source_files_file, app_classes_file, classpath_entries_file, compiled_outputs_root = (
                self._make_inputs(temp_root)
            )
            stage_output_dir = temp_root / "arodnap-out" / "stages" / "rlfixer"

            bundle = generate_rlfixer_compatibility_bundle(
                workspace_root=workspace_root,
                source_files_file=source_files_file,
                app_classes_file=app_classes_file,
                classpath_entries_file=classpath_entries_file,
                compiled_outputs_root=compiled_outputs_root,
                stage_output_dir=stage_output_dir,
            )

            self.assertEqual(bundle.root, (stage_output_dir / "compat_bundle").resolve())
            self.assertEqual(
                bundle.classes_file.read_text().splitlines(),
                ["com.example.App", "com.example.App$Nested"],
            )
            self.assertEqual(
                bundle.sources_file.read_text().splitlines(),
                ["src/main/java/com/example/App.java"],
            )
            metadata = json.loads(bundle.metadata_path.read_text())
            self.assertEqual(metadata["project_name"], workspace_root.name)
            self.assertEqual(metadata["jar_path"], str(bundle.jar_path))

    def test_generated_jar_contains_compiled_classes_and_dependency_contents(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            workspace_root, source_files_file, app_classes_file, classpath_entries_file, compiled_outputs_root = (
                self._make_inputs(temp_root)
            )
            stage_output_dir = temp_root / "arodnap-out" / "stages" / "rlfixer"

            bundle = generate_rlfixer_compatibility_bundle(
                workspace_root=workspace_root,
                source_files_file=source_files_file,
                app_classes_file=app_classes_file,
                classpath_entries_file=classpath_entries_file,
                compiled_outputs_root=compiled_outputs_root,
                stage_output_dir=stage_output_dir,
            )

            with ZipFile(bundle.jar_path) as archive:
                names = sorted(archive.namelist())
                self.assertIn("com/example/App.class", names)
                self.assertIn("com/example/App$Nested.class", names)
                self.assertIn("org/example/Dependency.class", names)
                self.assertIn("META-INF/services/demo.Service", names)
                self.assertNotIn("META-INF/MANIFEST.MF", names)
                self.assertEqual(archive.read("com/example/App.class"), b"app-class")

    def test_missing_inputs_fail_clearly(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            workspace_root = temp_root / "workspace"
            workspace_root.mkdir()
            compiled_outputs_root = workspace_root / "build" / "classes" / "java" / "main"
            compiled_outputs_root.mkdir(parents=True)

            with self.assertRaisesRegex(RLFixerCompatibilityBundleError, "Missing source files file"):
                generate_rlfixer_compatibility_bundle(
                    workspace_root=workspace_root,
                    source_files_file=temp_root / "missing-sources.txt",
                    app_classes_file=temp_root / "classes.txt",
                    classpath_entries_file=temp_root / "classpath.txt",
                    compiled_outputs_root=compiled_outputs_root,
                    stage_output_dir=temp_root / "arodnap-out" / "stages" / "rlfixer",
                )

    def _make_inputs(self, root: Path) -> tuple[Path, Path, Path, Path, Path]:
        workspace_root = root / "workspace"
        source_file = workspace_root / "src" / "main" / "java" / "com" / "example" / "App.java"
        source_file.parent.mkdir(parents=True)
        source_file.write_text("class App {}\n")

        compiled_outputs_root = workspace_root / "build" / "classes" / "java" / "main"
        (compiled_outputs_root / "com" / "example").mkdir(parents=True)
        (compiled_outputs_root / "com" / "example" / "App.class").write_bytes(b"app-class")
        (compiled_outputs_root / "com" / "example" / "App$Nested.class").write_bytes(b"nested-class")

        dependency_jar = root / "dependency.jar"
        with ZipFile(dependency_jar, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
            archive.writestr("org/example/Dependency.class", b"dependency-class")
            archive.writestr("META-INF/services/demo.Service", b"demo.impl.Service")

        source_files_file = root / "source-files.txt"
        source_files_file.write_text(str(source_file.resolve()) + "\n")

        app_classes_file = root / "app-classes.txt"
        app_classes_file.write_text("com.example.App\ncom.example.App$Nested\n")

        classpath_entries_file = root / "classpath-entries.txt"
        classpath_entries_file.write_text(
            "\n".join(
                [
                    str(compiled_outputs_root.resolve()),
                    str(dependency_jar.resolve()),
                ]
            )
            + "\n"
        )

        return (
            workspace_root,
            source_files_file,
            app_classes_file,
            classpath_entries_file,
            compiled_outputs_root,
        )


if __name__ == "__main__":
    unittest.main()
