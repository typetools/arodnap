from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Sequence

from arodnap.contracts import Timeouts

from .base import BuildAdapterContract, BuildToolSelection, UnsupportedProjectError
from .captured import (
    AntCaptureAdapter,
    CommandCaptureAdapter,
    GradleCaptureAdapter,
    MavenCaptureAdapter,
)


@dataclass(frozen=True)
class RegisteredBuildAdapter:
    selection: BuildToolSelection
    factory: Callable[..., BuildAdapterContract]
    # Executable names (from `-- <command>`) that this adapter knows how to hook into.
    executables: tuple[str, ...] = ()

    def create(
        self,
        repo_root: Path,
        *,
        compile_target: str | None = None,
        build_args: list[str] | None = None,
        build_command: Sequence[str] = (),
        timeouts: Timeouts | None = None,
    ) -> BuildAdapterContract:
        return self.factory(
            repo_root,
            compile_target=compile_target,
            build_args=build_args,
            build_command=build_command,
            timeouts=timeouts,
        )


# Detection order when no build command is given: the first adapter whose build file exists.
BUILD_ADAPTER_REGISTRY: tuple[RegisteredBuildAdapter, ...] = (
    RegisteredBuildAdapter(
        selection=BuildToolSelection(build_system="gradle", adapter_name="gradle"),
        factory=GradleCaptureAdapter,
        executables=("gradle", "gradlew"),
    ),
    RegisteredBuildAdapter(
        selection=BuildToolSelection(build_system="maven", adapter_name="maven"),
        factory=MavenCaptureAdapter,
        executables=("mvn", "mvnw"),
    ),
    RegisteredBuildAdapter(
        selection=BuildToolSelection(build_system="ant", adapter_name="ant"),
        factory=AntCaptureAdapter,
        executables=("ant",),
    ),
)
COMMAND_ADAPTER = RegisteredBuildAdapter(
    selection=BuildToolSelection(build_system="command", adapter_name="command"),
    factory=CommandCaptureAdapter,
)


def default_build_tool_selection() -> BuildToolSelection:
    return BUILD_ADAPTER_REGISTRY[0].selection


def select_build_adapter(
    repo_root: Path,
    *,
    compile_target: str | None = None,
    build_args: list[str] | None = None,
    build_command: Sequence[str] = (),
    timeouts: Timeouts | None = None,
) -> BuildAdapterContract:
    options = {
        "compile_target": compile_target,
        "build_args": build_args,
        "build_command": tuple(build_command),
        "timeouts": timeouts,
    }
    if build_command:
        executable = Path(build_command[0]).name
        for registration in BUILD_ADAPTER_REGISTRY:
            if executable in registration.executables:
                return registration.create(repo_root, **options)
        return COMMAND_ADAPTER.create(repo_root, **options)

    for registration in BUILD_ADAPTER_REGISTRY:
        adapter = registration.create(repo_root, **options)
        try:
            adapter.detect()
        except UnsupportedProjectError:
            continue
        return adapter
    raise UnsupportedProjectError(
        f"No Gradle, Maven or Ant build file found in {repo_root}. "
        "For other builds, pass the build command after --, e.g. `arodnap repair <repo> -- ./build.sh`."
    )


__all__ = [
    "BUILD_ADAPTER_REGISTRY",
    "COMMAND_ADAPTER",
    "RegisteredBuildAdapter",
    "default_build_tool_selection",
    "select_build_adapter",
]
