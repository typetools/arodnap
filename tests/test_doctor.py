import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

from arodnap.build_adapters.base import ProjectModel, UnsupportedProjectError
from arodnap.contracts import RunConfig, Timeouts
from arodnap.doctor import DoctorCheck, run_doctor


FIXTURES_ROOT = Path(__file__).resolve().parent / "fixtures"


class DoctorCommandTest(unittest.TestCase):
    def test_supported_repo_writes_json_and_reports_adapter_and_compile_target(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repo_root = root / "repo"
            repo_root.mkdir()
            out_dir = root / "out"
            config = self._make_config(repo_root=repo_root, out_dir=out_dir)
            observed: dict[str, object] = {}

            class FakeAdapter:
                adapter_name = "gradle-v1"
                build_system = "gradle"

                def inspect(self_nonlocal) -> ProjectModel:
                    workspace_root = observed["workspace_root"]
                    return ProjectModel(
                        repo_root=workspace_root,
                        build_file=workspace_root / "build.gradle",
                        build_system="gradle",
                        adapter_name="gradle-v1",
                        build_tool=("./gradlew",),
                        build_tool_source="wrapper",
                        compile_target="classes",
                        source_root=workspace_root / "src" / "main" / "java",
                        compiled_classes_root=workspace_root / "build" / "classes" / "java" / "main",
                    )

                def validate_compile(self_nonlocal, project: ProjectModel) -> None:
                    observed["validated_compile_target"] = project.compile_target

            def fake_select_build_adapter(repo_root, *, compile_target, build_args, build_command=()):
                observed["workspace_root"] = repo_root.resolve()
                observed["compile_target"] = compile_target
                observed["build_args"] = list(build_args)
                return FakeAdapter()

            with patch("arodnap.doctor._run_environment_checks", return_value=[self._ok_env_check()]):
                with patch("arodnap.doctor.select_build_adapter", side_effect=fake_select_build_adapter):
                    stdout = io.StringIO()
                    with redirect_stdout(stdout):
                        exit_code = run_doctor(config)

            self.assertEqual(exit_code, 0)
            self.assertEqual(observed["compile_target"], "classes")
            self.assertEqual(observed["build_args"], ["--info"])
            self.assertEqual(observed["validated_compile_target"], "classes")

            payload = json.loads((out_dir / "doctor.json").read_text())
            self.assertTrue(payload["success"])
            checks = {entry["name"]: entry for entry in payload["checks"]}
            self.assertEqual(checks["python_runtime"]["status"], "ok")
            self.assertEqual(checks["adapter_selection"]["details"]["adapter_name"], "gradle-v1")
            self.assertEqual(
                checks["source_root"]["details"]["source_root"],
                str((repo_root / "src" / "main" / "java").resolve()),
            )
            self.assertEqual(checks["compile_target"]["details"]["compile_target"], "classes")
            self.assertEqual(checks["compile_target"]["details"]["build_tool_source"], "wrapper")
            self.assertIn("Doctor report:", stdout.getvalue())

    def test_unsupported_repo_reports_adapter_backed_shape_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir) / "repo"
            repo_root.mkdir()
            (repo_root / "build.gradle").write_text("plugins { id 'java' }\n")
            out_dir = Path(temp_dir) / "out"
            config = self._make_config(repo_root=repo_root, out_dir=out_dir)

            with patch("arodnap.doctor._run_environment_checks", return_value=[self._ok_env_check()]):
                with patch(
                    "arodnap.build_adapters.captured.GradleCaptureAdapter.inspect",
                    side_effect=UnsupportedProjectError("The build compiled no Java sources, so there is nothing to analyze."),
                ):
                    stdout = io.StringIO()
                    with redirect_stdout(stdout):
                        exit_code = run_doctor(config)

            self.assertEqual(exit_code, 1)
            payload = json.loads((out_dir / "doctor.json").read_text())
            self.assertFalse(payload["success"])
            checks = {entry["name"]: entry for entry in payload["checks"]}
            self.assertEqual(checks["adapter_selection"]["status"], "ok")
            self.assertEqual(checks["repo_support"]["status"], "error")
            self.assertIn("compiled no Java sources", checks["repo_support"]["message"])
            self.assertNotIn("compile_target", checks)
            self.assertIn("[ERROR] repo_support", stdout.getvalue())

    def test_missing_repo_path_reports_error_and_stops_before_adapter_selection(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repo_root = root / "missing-repo"
            out_dir = root / "out"
            config = self._make_config(repo_root=repo_root, out_dir=out_dir)

            with patch("arodnap.doctor._run_environment_checks", return_value=[self._ok_env_check()]):
                with patch("arodnap.doctor.select_build_adapter") as select_build_adapter:
                    exit_code = run_doctor(config)

            self.assertEqual(exit_code, 1)
            select_build_adapter.assert_not_called()
            payload = json.loads((out_dir / "doctor.json").read_text())
            self.assertFalse(payload["success"])
            checks = {entry["name"]: entry for entry in payload["checks"]}
            self.assertEqual(checks["repo_path"]["status"], "error")
            self.assertNotIn("adapter_selection", checks)

    def test_compile_validation_failure_is_reported_in_json(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repo_root = root / "repo"
            repo_root.mkdir()
            out_dir = root / "out"
            config = self._make_config(repo_root=repo_root, out_dir=out_dir)
            observed: dict[str, object] = {}

            class FakeAdapter:
                adapter_name = "gradle-v1"
                build_system = "gradle"

                def inspect(self_nonlocal) -> ProjectModel:
                    workspace_root = observed["workspace_root"]
                    return ProjectModel(
                        repo_root=workspace_root,
                        build_file=workspace_root / "build.gradle",
                        build_system="gradle",
                        adapter_name="gradle-v1",
                        build_tool=("gradle",),
                        build_tool_source="system",
                        compile_target="check",
                        source_root=workspace_root / "src" / "main" / "java",
                        compiled_classes_root=workspace_root / "build" / "classes" / "java" / "main",
                    )

                def validate_compile(self_nonlocal, project: ProjectModel) -> None:
                    raise UnsupportedProjectError(
                        f"Gradle compile target '{project.compile_target}' failed for {project.repo_root}.\nboom"
                    )

            def fake_select_build_adapter(repo_root, *, compile_target, build_args, build_command=()):
                observed["workspace_root"] = repo_root.resolve()
                return FakeAdapter()

            with patch("arodnap.doctor._run_environment_checks", return_value=[self._ok_env_check()]):
                with patch("arodnap.doctor.select_build_adapter", side_effect=fake_select_build_adapter):
                    exit_code = run_doctor(config)

            self.assertEqual(exit_code, 1)
            payload = json.loads((out_dir / "doctor.json").read_text())
            checks = {entry["name"]: entry for entry in payload["checks"]}
            self.assertEqual(checks["compile_target"]["status"], "error")
            self.assertEqual(checks["compile_target"]["details"]["build_tool_source"], "system")
            self.assertIn(str(repo_root.resolve()), checks["compile_target"]["message"])

    def _make_config(self, *, repo_root: Path, out_dir: Path) -> RunConfig:
        return RunConfig(
            command="doctor",
            repo_root=repo_root,
            out_dir=out_dir,
            keep_workspace=False,
            workspace_mode="copy",
            build_args=["--info"],
            compile_target="classes",
            patch_dir=None,
            cf_root=repo_root / "checker-framework",
            close_injector_jar=repo_root / "close.jar",
            owning_field_jar=repo_root / "owning.jar",
            rlfixer_jar=repo_root / "rlfixer.jar",
            rlpatcher_jar=repo_root / "rlpatcher.jar",
            timeouts=Timeouts(build_seconds=900, analysis_seconds=1800, stage_seconds=900),
        )

    @staticmethod
    def _ok_env_check() -> DoctorCheck:
        return DoctorCheck(name="python_runtime", status="ok", message="env ok")


if __name__ == "__main__":
    unittest.main()
