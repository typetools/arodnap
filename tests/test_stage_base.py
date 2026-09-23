import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from arodnap.patch_tool import PatchExecution, PatchTool
from arodnap.runtime import CommandResult
from arodnap.stages.base import BaseNormalizedPatchStageWrapper, BaseStageWrapper, normalize_unified_diff_paths


class StageBaseWrapperTest(unittest.TestCase):
    def test_run_executes_shared_stage_lifecycle_in_order(self) -> None:
        calls: list[tuple[str, object]] = []

        class DemoStage(BaseStageWrapper):
            name = "demo"

            def validate_inputs(self, **kwargs: object) -> None:
                calls.append(("validate_inputs", kwargs["value"]))

            def invoke_tool(self, **kwargs: object) -> object:
                calls.append(("invoke_tool", kwargs["value"]))
                return int(kwargs["value"]) + 1

            def normalize_outputs(self, tool_result: object, **kwargs: object) -> object:
                calls.append(("normalize_outputs", tool_result))
                return {"normalized": int(tool_result) * 2}

            def validate_outputs(self, normalized_result: object, **kwargs: object) -> None:
                calls.append(("validate_outputs", normalized_result))

        result = DemoStage().run(value=3)

        self.assertEqual(result, {"normalized": 8})
        self.assertEqual(
            calls,
            [
                ("validate_inputs", 3),
                ("invoke_tool", 3),
                ("normalize_outputs", 4),
                ("validate_outputs", {"normalized": 8}),
            ],
        )

    def test_run_defaults_to_identity_normalization(self) -> None:
        class IdentityStage(BaseStageWrapper):
            name = "identity"

            def invoke_tool(self, **kwargs: object) -> object:
                return {"raw": kwargs["value"]}

        self.assertEqual(IdentityStage().run(value="payload"), {"raw": "payload"})

    def test_normalized_patch_stage_wrapper_noop_result_uses_shared_shape(self) -> None:
        class DemoPatchStage(BaseNormalizedPatchStageWrapper):
            name = "demo_patch"
            patch_filename = "demo.patch"
            raw_patch_filename = "demo.raw.patch"
            command_log_title = "demo_tool"

            def build_command(self, **kwargs: object) -> list[str]:
                return ["demo-tool", "--run"]

            def diagnostics_label(self) -> str:
                return "demo patch"

            def tool_failure_message(self, *, workspace_root: Path, log_path: Path) -> str:
                return f"demo tool failed for {workspace_root}. See log: {log_path}"

            def patch_apply_failure_message(self, *, log_path: Path) -> str:
                return f"demo patch apply failed. See log: {log_path}"

            def no_patch_note(self) -> str:
                return "No demo patch was generated."

            def changed_note(self, *, changed_files: list[str]) -> str:
                return f"Applied demo patch affecting {len(changed_files)} file(s)."

        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            workspace_root = temp_root / "workspace"
            (workspace_root / "src").mkdir(parents=True)
            diagnostics_path = temp_root / "diagnostics.txt"
            diagnostics_path.write_text("warning\n")
            stage_output_dir = temp_root / "out" / "stages" / "demo"

            with patch(
                "arodnap.stages.base.run_stage_command",
                return_value=CommandResult(
                    command=("demo-tool", "--run"),
                    cwd=workspace_root.resolve(),
                    returncode=0,
                    stdout="ok\n",
                    stderr="",
                ),
            ):
                result = DemoPatchStage().run(
                    config=object(),
                    workspace_root=workspace_root,
                    diagnostics_path=diagnostics_path,
                    stage_output_dir=stage_output_dir,
                )

            self.assertFalse(result.changed)
            self.assertFalse(result.rerun_required)
            self.assertEqual(result.changed_files, [])
            self.assertEqual(result.notes, ["No demo patch was generated."])
            self.assertTrue((stage_output_dir / "stage_result.json").is_file())
            self.assertFalse((stage_output_dir / "demo.patch").exists())

    def test_normalized_patch_stage_wrapper_changed_result_applies_shared_patch_flow(self) -> None:
        class DemoPatchStage(BaseNormalizedPatchStageWrapper):
            name = "demo_patch"
            patch_filename = "demo.patch"
            raw_patch_filename = "demo.raw.patch"
            command_log_title = "demo_tool"

            def build_command(self, **kwargs: object) -> list[str]:
                return ["demo-tool", "--run"]

            def diagnostics_label(self) -> str:
                return "demo patch"

            def tool_failure_message(self, *, workspace_root: Path, log_path: Path) -> str:
                return f"demo tool failed for {workspace_root}. See log: {log_path}"

            def patch_apply_failure_message(self, *, log_path: Path) -> str:
                return f"demo patch apply failed. See log: {log_path}"

            def no_patch_note(self) -> str:
                return "No demo patch was generated."

            def changed_note(self, *, changed_files: list[str]) -> str:
                return f"Applied demo patch affecting {len(changed_files)} file(s)."

        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            workspace_root = temp_root / "workspace"
            source_file = workspace_root / "src" / "main" / "java" / "Demo.java"
            source_file.parent.mkdir(parents=True)
            source_file.write_text("class Demo {}\n")
            diagnostics_path = temp_root / "diagnostics.txt"
            diagnostics_path.write_text("warning\n")
            stage_output_dir = temp_root / "out" / "stages" / "demo"
            raw_patch_path = stage_output_dir / "demo.raw.patch"

            def fake_stage_command(*, command: list[str], cwd: Path) -> CommandResult:
                raw_patch_path.write_text(
                    "\n".join(
                        [
                            f"--- {source_file.resolve()}",
                            f"+++ {source_file.resolve()}",
                            "@@ -1 +1 @@",
                            "-class Demo {}",
                            "+final class Demo {}",
                            "",
                        ]
                    )
                )
                return CommandResult(
                    command=tuple(command),
                    cwd=cwd.resolve(),
                    returncode=0,
                    stdout="patched\n",
                    stderr="",
                )

            with patch("arodnap.stages.base.run_stage_command", side_effect=fake_stage_command):
                with patch(
                    "arodnap.stages.base.apply_normalized_patch",
                    return_value=PatchExecution(
                        tool=PatchTool(binary="/usr/local/bin/gpatch", flavor="gnu", version="GNU patch 2.7.6"),
                        command=["/usr/local/bin/gpatch", "-p", "0", "-u"],
                        completed=CommandResult(
                            command=("/usr/local/bin/gpatch", "-p", "0", "-u"),
                            cwd=workspace_root.resolve(),
                            returncode=0,
                            stdout="applied\n",
                            stderr="",
                        ),
                    ),
                ):
                    result = DemoPatchStage().run(
                        config=object(),
                        workspace_root=workspace_root,
                        diagnostics_path=diagnostics_path,
                        stage_output_dir=stage_output_dir,
                    )

            self.assertTrue(result.changed)
            self.assertTrue(result.rerun_required)
            self.assertEqual(result.changed_files, ["src/main/java/Demo.java"])
            self.assertTrue((stage_output_dir / "demo.patch").is_file())
            self.assertFalse(raw_patch_path.exists())
            self.assertTrue((stage_output_dir / "stage_result.json").is_file())


if __name__ == "__main__":
    unittest.main()


class NormalizeUnifiedDiffPathsTest(unittest.TestCase):
    def test_header_pair_with_a_temporary_copy_resolves_to_the_workspace_file(self) -> None:
        # OwningFieldFixer diffs the workspace file against its edited temporary copy.
        with tempfile.TemporaryDirectory() as temp_dir:
            workspace = Path(temp_dir).resolve() / "workspace"
            source = workspace / "src/main/java/owning/Sink.java"
            source.parent.mkdir(parents=True)
            source.write_text("class Sink { FileWriter out; }\n")
            patch_text = (
                f"--- {source}\t2026-09-23 15:07:14\n"
                "+++ /var/folders/T/patch-3066328583808330310.java\t2026-09-23 15:07:14\n"
                "@@ -1 +1 @@\n"
                "-class Sink { FileWriter out; }\n"
                "+class Sink { private final FileWriter out; }\n"
            )

            normalized, changed = normalize_unified_diff_paths(patch_text, workspace_root=workspace)

        self.assertEqual(changed, ["src/main/java/owning/Sink.java"])
        self.assertTrue(normalized.startswith(
            "--- src/main/java/owning/Sink.java\t2026-09-23 15:07:14\n"
            "+++ src/main/java/owning/Sink.java\t2026-09-23 15:07:14\n"
        ))
