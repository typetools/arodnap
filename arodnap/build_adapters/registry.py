from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from .base import BuildAdapterContract, BuildToolSelection, UnsupportedProjectError
from .gradle import GradleAdapter


@dataclass(frozen=True)
class RegisteredBuildAdapter:
    selection: BuildToolSelection
    factory: Callable[..., BuildAdapterContract]

    def create(
        self,
        repo_root: Path,
        *,
        compile_target: str | None = None,
        build_args: list[str] | None = None,
    ) -> BuildAdapterContract:
        return self.factory(
            repo_root,
            compile_target=compile_target,
            build_args=build_args,
        )


BUILD_ADAPTER_REGISTRY: tuple[RegisteredBuildAdapter, ...] = (
    RegisteredBuildAdapter(
        selection=BuildToolSelection(build_system="gradle", adapter_name="gradle-v1"),
        factory=GradleAdapter,
    ),
)


def default_build_tool_selection() -> BuildToolSelection:
    return BUILD_ADAPTER_REGISTRY[0].selection


def select_build_adapter(
    repo_root: Path,
    *,
    compile_target: str | None = None,
    build_args: list[str] | None = None,
) -> BuildAdapterContract:
    last_error: UnsupportedProjectError | None = None

    for registration in BUILD_ADAPTER_REGISTRY:
        adapter = registration.create(
            repo_root,
            compile_target=compile_target,
            build_args=build_args,
        )
        try:
            adapter.detect()
        except UnsupportedProjectError as exc:
            last_error = exc
            continue
        return adapter

    if last_error is not None:
        raise last_error
    raise UnsupportedProjectError("No registered build adapter matched the target repository.")


__all__ = [
    "BUILD_ADAPTER_REGISTRY",
    "RegisteredBuildAdapter",
    "default_build_tool_selection",
    "select_build_adapter",
]
