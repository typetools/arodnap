import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from arodnap.cli.main import build_parser as build_cli_parser
from arodnap.cli.main import main as cli_main
from arodnap.main import build_parser, main


class CliTest(unittest.TestCase):
    def test_canonical_cli_import_path_matches_compat_wrapper(self) -> None:
        self.assertIs(build_parser, build_cli_parser)
        self.assertIs(main, cli_main)

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

    def test_time_limits_default_to_none_and_are_passed_to_the_config(self) -> None:
        from arodnap.orchestrator.config import build_run_config

        parser = build_parser()
        defaults = build_run_config(parser.parse_args(["repair", "/tmp/repo"]), command="repair")
        self.assertEqual(defaults.timeouts.to_dict(),
                         {"build_seconds": None, "analysis_seconds": None, "stage_seconds": None})

        args = parser.parse_args(
            ["repair", "--build-timeout", "600", "--analysis-timeout", "3600", "--stage-timeout", "120", "/tmp/repo"]
        )
        limits = build_run_config(args, command="repair").timeouts
        self.assertEqual((limits.build_seconds, limits.analysis_seconds, limits.stage_seconds), (600, 3600, 120))

    def test_time_limits_must_be_positive_whole_seconds(self) -> None:
        parser = build_parser()
        for value in ("0", "-5", "1.5", "ten"):
            with self.subTest(value=value), self.assertRaises(SystemExit):
                parser.parse_args(["repair", "--stage-timeout", value, "/tmp/repo"])

    def test_failures_are_reported_without_a_traceback(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            out_dir = Path(temp_dir) / "out"
            out_dir.mkdir()
            (out_dir / "report.json").write_text("{}")
            with patch("arodnap.orchestrator.pipeline.run_analyze", side_effect=RuntimeError("no build file")), \
                    patch("sys.stderr") as stderr:
                code = main(["analyze", "--out-dir", str(out_dir), "/tmp/repo"])
            printed = "".join(call.args[0] for call in stderr.write.call_args_list)
        self.assertEqual(code, 1)
        self.assertIn("arodnap: analyze failed: no build file", printed)
        self.assertIn("report.json", printed)

    def test_debug_mode_keeps_the_traceback(self) -> None:
        with patch.dict("os.environ", {"ARODNAP_DEBUG": "1"}), \
                patch("arodnap.orchestrator.pipeline.run_analyze", side_effect=RuntimeError("boom")):
            with self.assertRaisesRegex(RuntimeError, "boom"):
                main(["analyze", "/tmp/repo"])

    def test_apply_requires_patch_dir(self) -> None:
        parser = build_parser()

        with self.assertRaises(SystemExit):
            parser.parse_args(["apply", "/tmp/repo"])

    def test_doctor_parses_defaults(self) -> None:
        parser = build_parser()
        args = parser.parse_args(["doctor", "/tmp/repo"])

        self.assertEqual(args.command, "doctor")
        self.assertEqual(args.repo_root, "/tmp/repo")
        self.assertFalse(args.keep_workspace)
        self.assertEqual(args.build_args, [])
        self.assertIsNone(args.compile_target)
        self.assertIsNone(getattr(args, "patch_dir", None))
        self.assertTrue(args.out_dir.endswith("arodnap-out"))

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

    def test_doctor_dispatches_to_doctor_module(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir) / "repo"
            repo_root.mkdir()

            with patch("arodnap.doctor.run_doctor", return_value=0) as run_doctor:
                self.assertEqual(main(["doctor", str(repo_root)]), 0)

        config = run_doctor.call_args.args[0]
        self.assertEqual(config.command, "doctor")
        self.assertEqual(config.repo_root, repo_root.resolve())
        self.assertIsNone(config.patch_dir)


if __name__ == "__main__":
    unittest.main()
