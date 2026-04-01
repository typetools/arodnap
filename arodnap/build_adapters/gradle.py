from __future__ import annotations

import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

from .base import GradleProject, MissingBuildToolError, UnsupportedProjectError

_BUILD_FILES = ("build.gradle", "build.gradle.kts")
_SETTINGS_FILES = ("settings.gradle", "settings.gradle.kts")
_MULTI_MODULE_PATTERN = re.compile(r"^\s*include(?:Build|Flat)?\b|^\s*include\s*\(", re.MULTILINE)


class GradleAdapter:
    def __init__(self, repo_root: Path, *, compile_target: str | None = None) -> None:
        self.repo_root = repo_root.resolve()
        self.compile_target = compile_target or "classes"

    def inspect(self) -> GradleProject:
        build_file = self._detect_build_file()
        self._reject_multi_module()
        source_root = self._detect_source_root()
        build_tool = self.detect_build_tool()
        project = GradleProject(
            repo_root=self.repo_root,
            build_file=build_file,
            build_tool=build_tool,
            compile_target=self.compile_target,
            source_root=source_root,
        )
        self.validate_compile(project)
        return project

    def detect_build_tool(self) -> tuple[str, ...]:
        wrapper = self.repo_root / "gradlew"
        if wrapper.is_file() and os.access(wrapper, os.X_OK):
            return ("./gradlew",)
        if shutil.which("gradle"):
            return ("gradle",)
        raise MissingBuildToolError("Gradle executable not found. Install gradle or provide ./gradlew.")

    def validate_compile(self, project: GradleProject) -> None:
        env = os.environ.copy()
        with tempfile.TemporaryDirectory(prefix="arodnap-gradle-home-") as gradle_home:
            env["GRADLE_USER_HOME"] = gradle_home
            completed = subprocess.run(
                [*project.build_tool, "--no-daemon", "--console=plain", project.compile_target],
                cwd=project.repo_root,
                env=env,
                capture_output=True,
                text=True,
                check=False,
            )
        if completed.returncode == 0:
            return
        output = (completed.stdout + completed.stderr).strip()
        raise UnsupportedProjectError(
            f"Gradle compile target '{project.compile_target}' failed for {project.repo_root}.\n{output}"
        )

    def _detect_build_file(self) -> Path:
        for filename in _BUILD_FILES:
            candidate = self.repo_root / filename
            if candidate.is_file():
                return candidate
        raise UnsupportedProjectError(
            f"Gradle repo root must contain one of {_BUILD_FILES}: {self.repo_root}"
        )

    def _reject_multi_module(self) -> None:
        for filename in _SETTINGS_FILES:
            settings_file = self.repo_root / filename
            if settings_file.is_file() and _MULTI_MODULE_PATTERN.search(settings_file.read_text()):
                raise UnsupportedProjectError("Multi-module Gradle repos are not supported in v1.")

        nested_builds = []
        for filename in _BUILD_FILES:
            for path in self.repo_root.rglob(filename):
                if path.parent == self.repo_root:
                    continue
                relative = path.relative_to(self.repo_root)
                if relative.parts[0] in {"buildSrc", ".gradle", "build"}:
                    continue
                nested_builds.append(path)
        if nested_builds:
            raise UnsupportedProjectError("Multi-module Gradle repos are not supported in v1.")

    def _detect_source_root(self) -> Path:
        source_root = self.repo_root / "src" / "main" / "java"
        if source_root.is_dir():
            return source_root
        raise UnsupportedProjectError("Gradle repo must use src/main/java in v1.")
