import json
import tempfile
import unittest
from pathlib import Path

from arodnap.apply_support import ApplyError, apply_patch_bundle
from arodnap.contracts import RunConfig, Timeouts


class ApplyBundleTest(unittest.TestCase):
    def test_successful_dry_run_and_apply_honors_strip_level_and_target_root(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            repo_root = self._make_repo(temp_root, {"module/src/demo.txt": "old value\n"})
            patch_dir = self._make_patch_bundle(
                temp_root / "bundle",
                [
                    self._patch_entry(
                        repo_root=repo_root,
                        changed_file="module/src/demo.txt",
                        patch_relpath="patches/demo.patch",
                        patch_text=self._unified_patch(
                            old_header="prefix/src/demo.txt",
                            new_header="prefix/src/demo.txt",
                            old_line="old value",
                            new_line="new value",
                        ),
                        strip_level=1,
                        target_root="module",
                    )
                ],
            )

            apply_patch_bundle(self._config(repo_root=repo_root, patch_dir=patch_dir))

            self.assertEqual((repo_root / "module" / "src" / "demo.txt").read_text(), "new value\n")
            apply_log = (repo_root.parent / "out" / "logs" / "apply.log").read_text()
            self.assertIn("PATCH_BINARY:", apply_log)
            self.assertIn("PATCH_VERSION:", apply_log)

    def test_malformed_manifest_fails_clearly(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            repo_root = self._make_repo(temp_root, {"file.txt": "hello\n"})
            patch_dir = temp_root / "bundle"
            patch_dir.mkdir()
            (patch_dir / "patch_manifest.json").write_text("{invalid json\n")

            with self.assertRaisesRegex(ApplyError, "Malformed patch manifest JSON"):
                apply_patch_bundle(self._config(repo_root=repo_root, patch_dir=patch_dir))

    def test_hash_mismatch_fails_before_apply(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            repo_root = self._make_repo(temp_root, {"module/src/demo.txt": "old value\n"})
            patch_dir = self._make_patch_bundle(
                temp_root / "bundle",
                [
                    self._patch_entry(
                        repo_root=repo_root,
                        changed_file="module/src/demo.txt",
                        patch_relpath="patches/demo.patch",
                        patch_text=self._unified_patch(
                            old_header="prefix/src/demo.txt",
                            new_header="prefix/src/demo.txt",
                            old_line="old value",
                            new_line="new value",
                        ),
                        strip_level=1,
                        target_root="module",
                        override_hash="0" * 64,
                    )
                ],
            )

            with self.assertRaisesRegex(ApplyError, "Preimage hash mismatch"):
                apply_patch_bundle(self._config(repo_root=repo_root, patch_dir=patch_dir))

            self.assertEqual((repo_root / "module" / "src" / "demo.txt").read_text(), "old value\n")

    def test_dry_run_failure_leaves_target_repo_unchanged(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            repo_root = self._make_repo(
                temp_root,
                {
                    "module/src/one.txt": "one old\n",
                    "module/src/two.txt": "two old\n",
                },
            )
            patch_dir = self._make_patch_bundle(
                temp_root / "bundle",
                [
                    self._patch_entry(
                        repo_root=repo_root,
                        changed_file="module/src/one.txt",
                        patch_relpath="patches/one.patch",
                        patch_text=self._unified_patch(
                            old_header="prefix/src/one.txt",
                            new_header="prefix/src/one.txt",
                            old_line="one old",
                            new_line="one new",
                        ),
                        strip_level=1,
                        target_root="module",
                    ),
                    self._patch_entry(
                        repo_root=repo_root,
                        changed_file="module/src/two.txt",
                        patch_relpath="patches/two.patch",
                        patch_text=self._unified_patch(
                            old_header="prefix/src/two.txt",
                            new_header="prefix/src/two.txt",
                            old_line="does not match",
                            new_line="two new",
                        ),
                        strip_level=1,
                        target_root="module",
                    ),
                ],
            )

            with self.assertRaisesRegex(ApplyError, "dry-run validation failed"):
                apply_patch_bundle(self._config(repo_root=repo_root, patch_dir=patch_dir))

            self.assertEqual((repo_root / "module" / "src" / "one.txt").read_text(), "one old\n")
            self.assertEqual((repo_root / "module" / "src" / "two.txt").read_text(), "two old\n")

    def test_multiple_patches_apply_sequentially_after_full_validation(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            repo_root = self._make_repo(
                temp_root,
                {
                    "module/src/one.txt": "one old\n",
                    "module/src/two.txt": "two old\n",
                },
            )
            patch_dir = self._make_patch_bundle(
                temp_root / "bundle",
                [
                    self._patch_entry(
                        repo_root=repo_root,
                        changed_file="module/src/one.txt",
                        patch_relpath="patches/one.patch",
                        patch_text=self._unified_patch(
                            old_header="prefix/src/one.txt",
                            new_header="prefix/src/one.txt",
                            old_line="one old",
                            new_line="one new",
                        ),
                        strip_level=1,
                        target_root="module",
                    ),
                    self._patch_entry(
                        repo_root=repo_root,
                        changed_file="module/src/two.txt",
                        patch_relpath="patches/two.patch",
                        patch_text=self._unified_patch(
                            old_header="prefix/src/two.txt",
                            new_header="prefix/src/two.txt",
                            old_line="two old",
                            new_line="two new",
                        ),
                        strip_level=1,
                        target_root="module",
                    ),
                ],
            )

            apply_patch_bundle(self._config(repo_root=repo_root, patch_dir=patch_dir))

            self.assertEqual((repo_root / "module" / "src" / "one.txt").read_text(), "one new\n")
            self.assertEqual((repo_root / "module" / "src" / "two.txt").read_text(), "two new\n")

    def _config(self, *, repo_root: Path, patch_dir: Path) -> RunConfig:
        return RunConfig(
            command="apply",
            repo_root=repo_root.resolve(),
            out_dir=(repo_root.parent / "out").resolve(),
            keep_workspace=False,
            workspace_mode="copy",
            build_args=[],
            compile_target=None,
            patch_dir=patch_dir.resolve(),
            cf_root=(repo_root.parent / "cf").resolve(),
            close_injector_jar=(repo_root.parent / "close.jar").resolve(),
            owning_field_jar=(repo_root.parent / "owning.jar").resolve(),
            rlfixer_jar=(repo_root.parent / "rlfixer.jar").resolve(),
            rlpatcher_jar=(repo_root.parent / "rlpatcher.jar").resolve(),
            timeouts=Timeouts(),
        )

    def _make_repo(self, root: Path, files: dict[str, str]) -> Path:
        repo_root = root / "repo"
        repo_root.mkdir()
        for relative_path, contents in files.items():
            file_path = repo_root / relative_path
            file_path.parent.mkdir(parents=True, exist_ok=True)
            file_path.write_text(contents)
        return repo_root

    def _make_patch_bundle(self, bundle_root: Path, entries: list[dict[str, object]]) -> Path:
        bundle_root.mkdir(parents=True, exist_ok=True)
        (bundle_root / "patches").mkdir(exist_ok=True)
        manifest = {"stage": "rlpatcher", "patches": entries}
        (bundle_root / "patch_manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
        return bundle_root

    def _patch_entry(
        self,
        *,
        repo_root: Path,
        changed_file: str,
        patch_relpath: str,
        patch_text: str,
        strip_level: int,
        target_root: str,
        override_hash: str | None = None,
    ) -> dict[str, object]:
        patch_path = repo_root.parent / "bundle" / patch_relpath
        patch_path.parent.mkdir(parents=True, exist_ok=True)
        patch_path.write_text(patch_text)
        file_path = repo_root / changed_file
        return {
            "patch_file": patch_relpath,
            "stage": "rlpatcher",
            "strip_level": strip_level,
            "target_root": target_root,
            "changed_files": [changed_file],
            "preimage_hashes": {
                changed_file: override_hash
                or self._sha256(file_path.read_bytes())
            },
        }

    def _sha256(self, data: bytes) -> str:
        import hashlib

        return hashlib.sha256(data).hexdigest()

    def _unified_patch(
        self,
        *,
        old_header: str,
        new_header: str,
        old_line: str,
        new_line: str,
    ) -> str:
        return (
            f"--- {old_header}\n"
            f"+++ {new_header}\n"
            "@@ -1 +1 @@\n"
            f"-{old_line}\n"
            f"+{new_line}\n"
        )


if __name__ == "__main__":
    unittest.main()
