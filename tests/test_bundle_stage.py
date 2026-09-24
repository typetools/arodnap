import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from arodnap.stages.base import StageExecutionError
from arodnap.stages.bundle import run_bundle_stage


class BundleStageTest(unittest.TestCase):
    def test_no_changes_emits_empty_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root, workspace_root = self._make_trees(Path(temp_dir), {"src/A.java": "class A {}\n"})
            stage_dir = Path(temp_dir) / "out" / "stages" / "bundle"

            result = run_bundle_stage(
                repo_root=repo_root,
                workspace_root=workspace_root,
                candidate_files=["src/A.java"],
                stage_output_dir=stage_dir,
            )

            self.assertFalse(result.changed_files)
            self.assertEqual(json.loads((stage_dir / "patch_manifest.json").read_text())["patches"], [])
            self.assertFalse((stage_dir / "arodnap.patch").exists())

    def test_bundle_reproduces_workspace_from_original(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            files = {
                "src/A.java": "class A {\n    void a() {}\n}\n",
                "src/B.java": "class B {}",  # no trailing newline
                "src/Same.java": "class Same {}\n",
            }
            repo_root, workspace_root = self._make_trees(Path(temp_dir), files)
            (workspace_root / "src/A.java").write_text("class A {\n    void a() { close(); }\n}\n")
            (workspace_root / "src/B.java").write_text("final class B {}")
            stage_dir = Path(temp_dir) / "out" / "stages" / "bundle"

            result = run_bundle_stage(
                repo_root=repo_root,
                workspace_root=workspace_root,
                candidate_files=list(files) + ["src/A.java"],
                stage_output_dir=stage_dir,
            )

            self.assertEqual(result.changed_files, ["src/A.java", "src/B.java"])
            [entry] = json.loads((stage_dir / "patch_manifest.json").read_text())["patches"]
            self.assertEqual(entry["strip_level"], 0)
            self.assertEqual(entry["target_root"], ".")
            self.assertEqual(
                entry["preimage_hashes"]["src/B.java"],
                hashlib.sha256(files["src/B.java"].encode()).hexdigest(),
            )
            patch_text = (stage_dir / "arodnap.patch").read_text()
            self.assertIn("--- src/A.java\n+++ src/A.java\n", patch_text)
            self.assertIn("\\ No newline at end of file", patch_text)
            # The original tree is untouched; verification ran on a scratch copy.
            self.assertEqual((repo_root / "src/A.java").read_text(), files["src/A.java"])

    def test_files_not_in_the_repository_are_left_out(self) -> None:
        # e.g. annotation-processor output the build generated into the workspace
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root, workspace_root = self._make_trees(Path(temp_dir), {"src/A.java": "class A {}\n"})
            (workspace_root / "src/A.java").write_text("final class A {}\n")
            (workspace_root / "build/gen").mkdir(parents=True)
            (workspace_root / "build/gen/A_Gen.java").write_text("class A_Gen {}\n")

            result = run_bundle_stage(
                repo_root=repo_root,
                workspace_root=workspace_root,
                candidate_files=["src/A.java", "build/gen/A_Gen.java"],
                stage_output_dir=Path(temp_dir) / "out",
            )

        self.assertEqual(result.changed_files, ["src/A.java"])
        self.assertIn("Left out 1 generated file(s) that are not in the repository.", result.notes)

    def _make_trees(self, root: Path, files: dict[str, str]) -> tuple[Path, Path]:
        repo_root = root / "repo"
        workspace_root = root / "workspace"
        for tree in (repo_root, workspace_root):
            tree.mkdir()
            for relpath, text in files.items():
                path = tree / relpath
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text)
        return repo_root, workspace_root


if __name__ == "__main__":
    unittest.main()
