import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from arodnap.build_adapters import GradleAdapter, MissingBuildToolError, UnsupportedProjectError


FIXTURES_ROOT = Path(__file__).resolve().parent / "fixtures"


class GradleAdapterTest(unittest.TestCase):
    def test_supported_fixture_is_classified_supported(self) -> None:
        project = GradleAdapter(FIXTURES_ROOT / "gradle-pipeline-baseline").inspect()

        self.assertEqual(project.repo_root, (FIXTURES_ROOT / "gradle-pipeline-baseline").resolve())
        self.assertEqual(project.build_file.name, "build.gradle")
        self.assertEqual(project.build_tool, ("gradle",))
        self.assertEqual(project.compile_target, "classes")
        self.assertEqual(project.source_root, project.repo_root / "src" / "main" / "java")

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
