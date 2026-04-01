import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from arodnap.main import build_parser, main


class CliTest(unittest.TestCase):
    def test_analyze_parses_defaults(self) -> None:
        parser = build_parser()
        args = parser.parse_args(["analyze", "/tmp/repo"])

        self.assertEqual(args.command, "analyze")
        self.assertEqual(args.repo_root, "/tmp/repo")
        self.assertFalse(args.keep_workspace)
        self.assertEqual(args.build_args, [])
        self.assertIsNone(args.compile_target)
        self.assertIsNone(getattr(args, "patch_dir", None))
        self.assertTrue(args.out_dir.endswith("arodnap-out"))

    def test_apply_requires_patch_dir(self) -> None:
        parser = build_parser()

        with self.assertRaises(SystemExit):
            parser.parse_args(["apply", "/tmp/repo"])

    def test_analyze_dispatches_to_pipeline(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir) / "repo"
            repo_root.mkdir()

            with patch("arodnap.orchestrator.pipeline.run_analyze", return_value=7) as run_analyze:
                exit_code = main(
                    [
                        "analyze",
                        "--keep-workspace",
                        "--build-args=--info",
                        "--build-args=-x=test",
                        "--compile-target",
                        "classes",
                        str(repo_root),
                    ]
                )

        self.assertEqual(exit_code, 7)
        config = run_analyze.call_args.args[0]
        self.assertEqual(config.command, "analyze")
        self.assertEqual(config.repo_root, repo_root.resolve())
        self.assertTrue(config.keep_workspace)
        self.assertEqual(config.build_args, ["--info", "-x=test"])
        self.assertEqual(config.compile_target, "classes")
        self.assertIsNone(config.patch_dir)

    def test_apply_dispatches_to_pipeline(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir) / "repo"
            patch_dir = Path(temp_dir) / "patches"
            repo_root.mkdir()
            patch_dir.mkdir()

            with patch("arodnap.orchestrator.pipeline.run_apply", return_value=3) as run_apply:
                exit_code = main(["apply", "--patch-dir", str(patch_dir), str(repo_root)])

        self.assertEqual(exit_code, 3)
        config = run_apply.call_args.args[0]
        self.assertEqual(config.command, "apply")
        self.assertEqual(config.repo_root, repo_root.resolve())
        self.assertEqual(config.patch_dir, patch_dir.resolve())

    def test_infer_and_repair_dispatch_to_pipeline(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir) / "repo"
            repo_root.mkdir()

            with patch("arodnap.orchestrator.pipeline.run_infer", return_value=0) as run_infer:
                self.assertEqual(main(["infer", str(repo_root)]), 0)
            with patch("arodnap.orchestrator.pipeline.run_repair", return_value=0) as run_repair:
                self.assertEqual(main(["repair", str(repo_root)]), 0)

        self.assertEqual(run_infer.call_args.args[0].command, "infer")
        self.assertEqual(run_repair.call_args.args[0].command, "repair")


if __name__ == "__main__":
    unittest.main()
