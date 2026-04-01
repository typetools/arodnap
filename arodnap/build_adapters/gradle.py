from __future__ import annotations

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

from .base import AdapterExecutionError, GradleProject, MissingBuildToolError, UnsupportedProjectError

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
            compiled_classes_root=self.repo_root / "build" / "classes" / "java" / "main",
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

    def write_source_files_file(self, project: GradleProject, output_path: Path) -> Path:
        source_files = sorted(
            path.resolve()
            for path in project.source_root.rglob("*.java")
            if path.is_file()
        )
        output_path.parent.mkdir(parents=True, exist_ok=True)
        contents = "\n".join(str(path) for path in source_files)
        output_path.write_text(f"{contents}\n" if contents else "")
        return output_path

    def write_app_classes_file(self, project: GradleProject, output_path: Path) -> Path:
        classes_root = project.compiled_classes_root
        if not classes_root.is_dir():
            raise UnsupportedProjectError(
                f"Compiled classes directory not found after validation: {classes_root}"
            )

        class_names = []
        for path in classes_root.rglob("*.class"):
            if not path.is_file() or path.name == "module-info.class":
                continue
            relative = path.relative_to(classes_root).with_suffix("")
            class_names.append(".".join(relative.parts))
        class_names.sort()

        output_path.parent.mkdir(parents=True, exist_ok=True)
        contents = "\n".join(class_names)
        output_path.write_text(f"{contents}\n" if contents else "")
        return output_path

    def write_classpath_entries_file(self, project: GradleProject, output_path: Path) -> Path:
        with tempfile.TemporaryDirectory(
            prefix=".arodnap-gradle-init-",
            dir=project.repo_root,
        ) as temp_dir:
            init_script = Path(temp_dir) / "classpath.init.gradle"
            init_script.write_text(_classpath_init_script())
            completed = self._run_gradle(
                project,
                "-q",
                "-I",
                str(init_script),
                "arodnapPrintMainClasspath",
            )

        if completed.returncode != 0:
            output = (completed.stdout + completed.stderr).strip()
            raise AdapterExecutionError(
                f"Gradle classpath extraction failed for {project.repo_root}.\n{output}"
            )

        entries = sorted(
            {
                str(Path(line.strip()).resolve())
                for line in completed.stdout.splitlines()
                if line.strip() and Path(line.strip()).is_absolute()
            }
        )
        if not entries:
            raise AdapterExecutionError(
                f"Gradle classpath extraction produced no entries for {project.repo_root}."
            )

        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text("\n".join(entries) + "\n")
        return output_path

    def write_adapter_metadata_file(
        self,
        project: GradleProject,
        *,
        source_files_file: Path,
        app_classes_file: Path,
        classpath_entries_file: Path,
        output_path: Path,
    ) -> Path:
        metadata = {
            "repo_root": str(project.repo_root),
            "build_file": str(project.build_file),
            "build_tool": list(project.build_tool),
            "compile_target": project.compile_target,
            "source_root": str(project.source_root),
            "compiled_classes_root": str(project.compiled_classes_root),
            "source_files_file": str(source_files_file),
            "app_classes_file": str(app_classes_file),
            "classpath_entries_file": str(classpath_entries_file),
        }
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n")
        return output_path

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

    def _run_gradle(self, project: GradleProject, *args: str) -> subprocess.CompletedProcess[str]:
        env = os.environ.copy()
        with tempfile.TemporaryDirectory(prefix="arodnap-gradle-home-") as gradle_home:
            env["GRADLE_USER_HOME"] = gradle_home
            return subprocess.run(
                [*project.build_tool, "--no-daemon", "--console=plain", *args],
                cwd=project.repo_root,
                env=env,
                capture_output=True,
                text=True,
                check=False,
            )


def _classpath_init_script() -> str:
    return """
allprojects {
    tasks.register("arodnapPrintMainClasspath") {
        doLast {
            def sourceSets = project.extensions.findByName("sourceSets")
            if (sourceSets == null) {
                return
            }
            def mainSourceSet = sourceSets.findByName("main")
            if (mainSourceSet == null) {
                return
            }
            def entries = []
            entries.addAll(mainSourceSet.output.classesDirs.files)
            entries.addAll(mainSourceSet.compileClasspath.files)
            entries.findAll { it != null }
                .collect { it.absolutePath }
                .toSet()
                .sort()
                .each { println(it) }
        }
    }
}
""".strip() + "\n"
