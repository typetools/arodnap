from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
import shutil
import tempfile
from collections.abc import Iterator


@dataclass(frozen=True)
class WorkspaceCopy:
    repo_root: Path
    workspace_root: Path
    managed_root: Path
    keep_workspace: bool

    def cleanup(self, force: bool = False) -> None:
        if self.keep_workspace and not force:
            return
        shutil.rmtree(self.managed_root, ignore_errors=True)


class WorkspaceManager:
    def __init__(self, temp_root: Path | None = None) -> None:
        self.temp_root = temp_root

    def create(self, repo_root: Path, keep_workspace: bool = False) -> WorkspaceCopy:
        repo_root = repo_root.resolve()
        if not repo_root.is_dir():
            raise ValueError(f"repo_root must be an existing directory: {repo_root}")

        managed_root = Path(
            tempfile.mkdtemp(prefix="arodnap-workspace-", dir=str(self.temp_root) if self.temp_root else None)
        )
        workspace_root = managed_root / repo_root.name
        shutil.copytree(repo_root, workspace_root, ignore=shutil.ignore_patterns(".git"))
        return WorkspaceCopy(
            repo_root=repo_root,
            workspace_root=workspace_root,
            managed_root=managed_root,
            keep_workspace=keep_workspace,
        )


@contextmanager
def copied_workspace(
    repo_root: Path,
    *,
    keep_workspace: bool = False,
    temp_root: Path | None = None,
) -> Iterator[WorkspaceCopy]:
    workspace = WorkspaceManager(temp_root=temp_root).create(
        repo_root=repo_root,
        keep_workspace=keep_workspace,
    )
    try:
        yield workspace
    finally:
        workspace.cleanup()
