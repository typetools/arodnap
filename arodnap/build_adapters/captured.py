"""Build adapters that learn a project's compilation by watching its own build.

Each adapter runs the project's build once in the workspace with a recording hook installed
through the build tool's official extension point, then hands the recorded javac invocations
to `capture.py`:

- Gradle: an init script records the inputs of every JavaCompile task.
- Maven: a core extension records every `maven-compiler-plugin:compile` execution
  (`-Dmaven.ext.class.path=<jar>`), whether or not the compiler forks.
- Ant: a recording compiler adapter (`-lib <jar> -Dbuild.compiler=<adapter>`).
- Any other command: the recording javac shim comes first on PATH.

The shim is also on PATH for Gradle, Maven and Ant, so scripts they call that run javac
directly are recorded too.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
import json
import os
from pathlib import Path
import shutil
import sys
import time

from arodnap.runtime import CommandExecutionError, JdkResolutionError, render_command_log, resolve_jdk, run_command

from .base import (
    AdapterExecutionError,
    AdapterMetadata,
    BuildToolSelection,
    MissingBuildToolError,
    ProjectModel,
    UnsupportedProjectError,
)
from .capture import AnalysisInputs, load_compile_units, merge_compile_units

ARODNAP_DIR = ".arodnap"
_PREBUILT_JARS = Path(__file__).resolve().parents[2] / "restructure_plugins" / "prebuilt_plugin_jars"
_ANT_CAPTURE_JAR = _PREBUILT_JARS / "arodnap-ant-capture.jar"
_MAVEN_CAPTURE_JAR = _PREBUILT_JARS / "arodnap-maven-capture.jar"
_ANT_ADAPTER_CLASS = "org.arodnap.capture.RecordingJavacAdapter"
_GRADLE_FLAGS = ("--no-daemon", "--console=plain", "--no-build-cache", "--no-configuration-cache")

_JAVAC_SHIM = '''\
"""Arodnap's recording javac: logs the invocation, then runs the real javac."""
import json, os, shlex, sys

def expand(args):
    expanded = []
    for arg in args:
        if arg.startswith("@") and os.path.isfile(arg[1:]):
            with open(arg[1:]) as argfile:
                expanded.extend(expand(shlex.split(argfile.read())))
        else:
            expanded.append(arg)
    return expanded

with open(os.environ["ARODNAP_CAPTURE_FILE"], "a") as capture:
    capture.write(json.dumps({"cwd": os.getcwd(), "args": expand(sys.argv[1:])}) + "\\n")
real_javac = os.environ["ARODNAP_REAL_JAVAC"]
os.execv(real_javac, [real_javac, *sys.argv[1:]])
'''

_GRADLE_INIT_SCRIPT = '''\
// Arodnap: record the inputs of every JavaCompile task that runs, as javac arguments.
import groovy.json.JsonOutput
allprojects {
    tasks.withType(JavaCompile).configureEach { task ->
        task.doFirst {
            def args = ["-d", task.destinationDirectory.get().asFile.absolutePath,
                        "-classpath", task.classpath.asPath]
            if (task.options.release.isPresent()) {
                args += ["--release", task.options.release.get().toString()]
            } else {
                args += ["-source", task.sourceCompatibility, "-target", task.targetCompatibility]
            }
            def generated = task.options.generatedSourceOutputDirectory.getOrNull()
            if (generated != null) {
                args += ["-s", generated.asFile.absolutePath]
            }
            def processorPath = task.options.annotationProcessorPath
            if (processorPath != null && !processorPath.isEmpty()) {
                args += ["-processorpath", processorPath.asPath]
            }
            if (task.options.encoding != null) {
                args += ["-encoding", task.options.encoding]
            }
            args += task.options.allCompilerArgs
            args += task.source.files.collect { it.absolutePath }.sort()
            new File(System.getenv("ARODNAP_CAPTURE_FILE")).append(
                JsonOutput.toJson([cwd: task.project.projectDir.absolutePath, task: task.path, args: args]) + "\\n")
        }
    }
}
'''


@dataclass(frozen=True)
class CapturedProject(ProjectModel):
    inputs: AnalysisInputs | None = None
    build_command: tuple[str, ...] = ()
    capture_file: Path | None = None
    build_log: Path | None = None


class CapturedBuildAdapter:
    """Shared capture flow; subclasses choose the build files, command and hook."""

    adapter_name: str
    build_system: str
    build_files: tuple[str, ...] = ()
    touch_sources_before_build = False

    def __init__(
        self,
        repo_root: Path,
        *,
        compile_target: str | None = None,
        build_args: list[str] | None = None,
        build_command: list[str] | tuple[str, ...] | None = None,
    ) -> None:
        self.repo_root = repo_root.resolve()
        self.compile_target = compile_target
        self.build_args = list(build_args or [])
        self.build_command = tuple(build_command or ())

    # Contract -------------------------------------------------------------------------

    def detect(self) -> BuildToolSelection:
        self._build_file()
        return BuildToolSelection(build_system=self.build_system, adapter_name=self.adapter_name)

    def inspect(self) -> CapturedProject:
        build_file = self._build_file()
        capture_dir = self.repo_root / ARODNAP_DIR / "capture"
        shutil.rmtree(capture_dir, ignore_errors=True)
        capture_dir.mkdir(parents=True)
        capture_file = capture_dir / "javac-invocations.jsonl"
        build_log = capture_dir / "build.log"
        shim_dir = self._write_javac_shim(capture_dir)

        command = self.capture_command(capture_dir=capture_dir, shim=shim_dir / "javac")
        if self.touch_sources_before_build:
            _touch_java_sources(self.repo_root)
        env = os.environ.copy()
        env.update(
            {
                "ARODNAP_CAPTURE_FILE": str(capture_file),
                "ARODNAP_REAL_JAVAC": _real_javac(),
                "PATH": f"{shim_dir}{os.pathsep}{env.get('PATH', '')}",
            }
        )
        try:
            result = run_command(list(command), cwd=self.repo_root, env=env)
        except CommandExecutionError as exc:
            raise MissingBuildToolError(f"Could not run the build command {command[0]!r}: {exc}") from exc
        build_log.write_text(render_command_log(result, tool_name=f"{self.build_system} build"))
        if result.returncode != 0:
            raise UnsupportedProjectError(
                f"The build failed (exit code {result.returncode}): {' '.join(command)}\n"
                f"{_tail(result.stdout + result.stderr)}"
            )

        inputs = merge_compile_units(load_compile_units(capture_file))
        # Ant puts its own runtime, including Arodnap's capture adapter, on javac's classpath.
        inputs = replace(
            inputs, classpath=tuple(entry for entry in inputs.classpath if entry.parent != _PREBUILT_JARS)
        )
        return CapturedProject(
            repo_root=self.repo_root,
            build_file=build_file,
            build_system=self.build_system,
            adapter_name=self.adapter_name,
            build_tool=(command[0],),
            build_tool_source=self._build_tool_source(command[0]),
            compile_target=self.compile_target or "",
            source_root=inputs.analysis_root,
            compiled_classes_root=self.repo_root / ARODNAP_DIR / "analysis-classes",
            inputs=inputs,
            build_command=tuple(command),
            capture_file=capture_file,
            build_log=build_log,
        )

    def validate_compile(self, project: ProjectModel) -> None:
        # The project's own build just compiled successfully while being captured.
        return None

    def write_source_files_file(self, project: ProjectModel, output_path: Path) -> Path:
        inputs = _inputs(project)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text("".join(f"{path}\n" for path in (*inputs.sources, *inputs.generated_sources)))
        return output_path

    def write_app_classes_file(self, project: ProjectModel, output_path: Path) -> Path:
        """Compile the merged sources into the analysis classes directory and list their classes."""
        inputs = _inputs(project)
        classes_root = project.compiled_classes_root
        shutil.rmtree(classes_root, ignore_errors=True)
        classes_root.mkdir(parents=True)
        sources_file = classes_root.parent / "analysis-sources.txt"
        sources_file.write_text(
            "".join(f'"{path}"\n'.replace("\\", "\\\\") for path in (*inputs.sources, *inputs.generated_sources))
        )
        command = [_real_javac(), "-d", str(classes_root), "-proc:none", "-nowarn", "-Xlint:none"]
        if inputs.classpath:
            command += ["-classpath", os.pathsep.join(str(entry) for entry in inputs.classpath)]
        if inputs.release is not None:
            command += ["--release", str(inputs.release)]
        if inputs.encoding:
            command += ["-encoding", inputs.encoding]
        command.append(f"@{sources_file}")
        try:
            result = run_command(command, cwd=self.repo_root)
        except CommandExecutionError as exc:
            raise AdapterExecutionError(str(exc)) from exc
        if result.returncode != 0:
            raise AdapterExecutionError(
                "Arodnap could not compile the captured sources together as one program:\n"
                f"{_tail(result.stdout + result.stderr)}"
            )

        class_names = sorted(
            ".".join(path.relative_to(classes_root).with_suffix("").parts)
            for path in classes_root.rglob("*.class")
            if path.name != "module-info.class"
        )
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text("".join(f"{name}\n" for name in class_names))
        return output_path

    def write_classpath_entries_file(self, project: ProjectModel, output_path: Path) -> Path:
        inputs = _inputs(project)
        entries = [project.compiled_classes_root, *inputs.classpath]
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text("".join(f"{entry}\n" for entry in entries))
        return output_path

    def write_adapter_metadata_file(
        self,
        project: ProjectModel,
        *,
        source_files_file: Path,
        app_classes_file: Path,
        classpath_entries_file: Path,
        output_path: Path,
    ) -> Path:
        inputs = _inputs(project)
        metadata = AdapterMetadata(
            repo_root=project.repo_root,
            build_file=project.build_file,
            build_system=project.build_system,
            adapter_name=project.adapter_name,
            build_tool=project.build_tool,
            build_tool_source=project.build_tool_source,
            compile_target=project.compile_target,
            source_root=project.source_root,
            compiled_classes_root=project.compiled_classes_root,
            source_files_file=source_files_file,
            app_classes_file=app_classes_file,
            classpath_entries_file=classpath_entries_file,
        )
        payload = metadata.to_payload()
        payload.update(
            {
                "build_command": list(project.build_command),
                "release": inputs.release,
                "encoding": inputs.encoding,
                "generated_source_count": len(inputs.generated_sources),
                "compile_units": [
                    {
                        "label": unit.label,
                        "cwd": str(unit.cwd),
                        "source_count": len(unit.sources),
                        "output_dir": str(unit.output_dir) if unit.output_dir else None,
                        "release": unit.release,
                    }
                    for unit in inputs.units
                ],
            }
        )
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")
        # Keep what the build did next to the metadata for debugging.
        for artifact in (project.capture_file, project.build_log):
            if artifact is not None and artifact.is_file():
                shutil.copyfile(artifact, output_path.parent / artifact.name)
        return output_path

    # Per build system ------------------------------------------------------------------

    def _build_tool_source(self, executable: str) -> str:
        return "wrapper" if executable.startswith("./") else "system"

    def capture_command(self, *, capture_dir: Path, shim: Path) -> tuple[str, ...]:
        raise NotImplementedError

    def _build_file(self) -> Path:
        for name in self.build_files:
            candidate = self.repo_root / name
            if candidate.is_file():
                return candidate
        raise UnsupportedProjectError(
            f"{self.build_system} build file ({' or '.join(self.build_files)}) not found in {self.repo_root}"
        )

    def _tool(self, wrapper: str, system: str) -> str:
        wrapper_path = self.repo_root / wrapper
        if wrapper_path.is_file() and os.access(wrapper_path, os.X_OK):
            return f"./{wrapper}"
        if shutil.which(system):
            return system
        raise MissingBuildToolError(f"{system} not found. Install it or add ./{wrapper} to the project.")

    @staticmethod
    def _write_javac_shim(capture_dir: Path) -> Path:
        shim_dir = capture_dir / "bin"
        shim_dir.mkdir()
        shim = shim_dir / "javac"
        shim.write_text(f"#!{sys.executable}\n{_JAVAC_SHIM}")
        shim.chmod(0o755)
        return shim_dir


class GradleCaptureAdapter(CapturedBuildAdapter):
    adapter_name = "gradle"
    build_system = "gradle"
    build_files = ("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")

    def capture_command(self, *, capture_dir: Path, shim: Path) -> tuple[str, ...]:
        init_script = capture_dir / "arodnap-capture.init.gradle"
        init_script.write_text(_GRADLE_INIT_SCRIPT)
        hook = (*_GRADLE_FLAGS, "-I", str(init_script))
        if self.build_command:
            return (self.build_command[0], *hook, *self.build_command[1:])
        tool = self._tool("gradlew", "gradle")
        return (tool, *hook, *self.build_args, "clean", self.compile_target or "compileJava")


class MavenCaptureAdapter(CapturedBuildAdapter):
    adapter_name = "maven"
    build_system = "maven"
    build_files = ("pom.xml",)

    def capture_command(self, *, capture_dir: Path, shim: Path) -> tuple[str, ...]:
        # A fork/executable override is ignored when the POM configures <fork> itself (the
        # Apache parent POM does), so record through a core extension instead.
        if not _MAVEN_CAPTURE_JAR.is_file():
            raise MissingBuildToolError(f"Arodnap's Maven capture jar is missing: {_MAVEN_CAPTURE_JAR}")
        hook = (f"-Dmaven.ext.class.path={_MAVEN_CAPTURE_JAR}",)
        if self.build_command:
            return (*self.build_command, *hook)
        tool = self._tool("mvnw", "mvn")
        return (tool, "-B", *self.build_args, "clean", self.compile_target or "compile", *hook)


class AntCaptureAdapter(CapturedBuildAdapter):
    adapter_name = "ant"
    build_system = "ant"
    build_files = ("build.xml",)
    # Ant only recompiles sources newer than their classes; the workspace copy keeps timestamps.
    touch_sources_before_build = True

    def capture_command(self, *, capture_dir: Path, shim: Path) -> tuple[str, ...]:
        if not _ANT_CAPTURE_JAR.is_file():
            raise MissingBuildToolError(f"Arodnap's Ant capture jar is missing: {_ANT_CAPTURE_JAR}")
        hook = ("-lib", str(_ANT_CAPTURE_JAR), f"-Dbuild.compiler={_ANT_ADAPTER_CLASS}")
        if self.build_command:
            return (self.build_command[0], *hook, *self.build_command[1:])
        if not shutil.which("ant"):
            raise MissingBuildToolError("ant not found. Install Apache Ant.")
        target = (self.compile_target,) if self.compile_target else ()
        return ("ant", *hook, *self.build_args, *target)


class CommandCaptureAdapter(CapturedBuildAdapter):
    """Any build command that runs the javac executable (shell scripts, Make, ...)."""

    adapter_name = "command"
    build_system = "command"
    touch_sources_before_build = True

    def _build_file(self) -> Path:
        if not self.build_command:
            raise UnsupportedProjectError("A build command is required: arodnap <command> <repo> -- <build command>")
        return self.repo_root

    def capture_command(self, *, capture_dir: Path, shim: Path) -> tuple[str, ...]:
        return self.build_command

    def _build_tool_source(self, executable: str) -> str:
        return "command"


def _inputs(project: ProjectModel) -> AnalysisInputs:
    inputs = getattr(project, "inputs", None)
    if inputs is None:
        raise AdapterExecutionError("Project was not captured from a build.")
    return inputs


def _real_javac() -> str:
    try:
        javac = resolve_jdk().home / "bin" / "javac"
    except JdkResolutionError:
        return shutil.which("javac") or "javac"
    return str(javac)


def _touch_java_sources(root: Path) -> None:
    now = time.time()
    for path in root.rglob("*.java"):
        if ARODNAP_DIR not in path.relative_to(root).parts:
            os.utime(path, (now, now))


def _tail(text: str, lines: int = 40) -> str:
    return "\n".join(text.strip().splitlines()[-lines:])


__all__ = [
    "AntCaptureAdapter",
    "CapturedBuildAdapter",
    "CapturedProject",
    "CommandCaptureAdapter",
    "GradleCaptureAdapter",
    "MavenCaptureAdapter",
]
