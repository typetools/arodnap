import tempfile
import unittest
from pathlib import Path

from arodnap import WorkspaceManager, copied_workspace


class WorkspaceManagerTest(unittest.TestCase):
    def _make_repo(self, root: Path) -> Path:
        repo_root = root / "repo"
        (repo_root / "src").mkdir(parents=True)
        (repo_root / ".git").mkdir()
        (repo_root / "src" / "Main.java").write_text("class Main {}\n")
        (repo_root / "README.md").write_text("original\n")
        return repo_root

    def test_workspace_copy_leaves_original_repo_untouched(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            repo_root = self._make_repo(temp_root)

            workspace = WorkspaceManager(temp_root=temp_root).create(repo_root)
            try:
                workspace_file = workspace.workspace_root / "README.md"
                workspace_file.write_text("workspace-only\n")
                (workspace.workspace_root / "new-file.txt").write_text("new\n")

                self.assertEqual((repo_root / "README.md").read_text(), "original\n")
                self.assertFalse((repo_root / "new-file.txt").exists())
                self.assertFalse((workspace.workspace_root / ".git").exists())
            finally:
                workspace.cleanup(force=True)

    def test_context_manager_cleans_up_workspace_by_default(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            repo_root = self._make_repo(temp_root)

            with copied_workspace(repo_root, temp_root=temp_root) as workspace:
                workspace_root = workspace.workspace_root
                managed_root = workspace.managed_root
                self.assertTrue(workspace_root.exists())

            self.assertFalse(workspace_root.exists())
            self.assertFalse(managed_root.exists())

    def test_keep_workspace_preserves_copy_until_forced_cleanup(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            repo_root = self._make_repo(temp_root)

            with copied_workspace(repo_root, keep_workspace=True, temp_root=temp_root) as workspace:
                workspace_root = workspace.workspace_root
                managed_root = workspace.managed_root

            self.assertTrue(workspace_root.exists())
            self.assertTrue(managed_root.exists())

            workspace.cleanup(force=True)

            self.assertFalse(workspace_root.exists())
            self.assertFalse(managed_root.exists())


if __name__ == "__main__":
    unittest.main()
