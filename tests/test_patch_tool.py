import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from arodnap.patch_tool import (
    PatchExecution,
    PatchTool,
    PatchToolError,
    append_patch_execution_log,
    discover_patch_tool,
    run_patch,
)
from arodnap.runtime import CommandExecutionError, CommandResult


class PatchToolTest(unittest.TestCase):
    def test_prefers_gpatch_when_available(self) -> None:
        with patch(
            "arodnap.patch_tool.shutil.which",
            side_effect=lambda name: {
                "gpatch": "/opt/homebrew/bin/gpatch",
                "patch": "/usr/bin/patch",
            }.get(name),
        ):
            with patch("arodnap.patch_tool.run_command", side_effect=self._fake_version_run):
                tool = discover_patch_tool(require_gnu=False, operation_label="apply")

        self.assertEqual(tool.binary, "/opt/homebrew/bin/gpatch")
        self.assertEqual(tool.flavor, "gnu")
        self.assertEqual(tool.version, "GNU patch 2.7.6")

    def test_uses_plain_patch_when_it_is_gnu(self) -> None:
        with patch(
            "arodnap.patch_tool.shutil.which",
            side_effect=lambda name: {
                "patch": "/usr/local/bin/patch",
            }.get(name),
        ):
            with patch("arodnap.patch_tool.run_command", side_effect=self._fake_version_run):
                tool = discover_patch_tool(require_gnu=False, operation_label="apply")

        self.assertEqual(tool.binary, "/usr/local/bin/patch")
        self.assertEqual(tool.flavor, "gnu")

    def test_fails_clearly_when_only_bsd_patch_is_available_for_gnu_required_operation(self) -> None:
        with patch(
            "arodnap.patch_tool.shutil.which",
            side_effect=lambda name: {
                "patch": "/usr/bin/patch",
            }.get(name),
        ):
            with patch(
                "arodnap.patch_tool.run_command",
                return_value=CommandResult(
                    command=("/usr/bin/patch", "--version"),
                    cwd=None,
                    returncode=0,
                    stdout="patch 2.0-12u11-Apple\n",
                    stderr="",
                ),
            ):
                with self.assertRaisesRegex(PatchToolError, "GNU patch is required for stage patch apply"):
                    discover_patch_tool(require_gnu=True, operation_label="stage patch apply")

    def test_append_patch_execution_log_includes_binary_and_version(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            log_path = Path(temp_dir) / "patch.log"
            execution = PatchExecution(
                tool=PatchTool(
                    binary="/opt/homebrew/bin/gpatch",
                    flavor="gnu",
                    version="GNU patch 2.7.6",
                ),
                command=["/opt/homebrew/bin/gpatch", "-p", "0", "-u", "-i", "demo.patch"],
                completed=CommandResult(
                    command=("/opt/homebrew/bin/gpatch", "-p", "0", "-u", "-i", "demo.patch"),
                    cwd=log_path.parent.resolve(),
                    returncode=0,
                    stdout="applied\n",
                    stderr="",
                ),
            )

            append_patch_execution_log(log_path, title="apply_patch", execution=execution)

            log_contents = log_path.read_text()
            self.assertIn("PATCH_BINARY: /opt/homebrew/bin/gpatch", log_contents)
            self.assertIn("PATCH_FLAVOR: gnu", log_contents)
            self.assertIn("PATCH_VERSION: GNU patch 2.7.6", log_contents)
            self.assertIn("TOOL: patch", log_contents)
            self.assertIn(f"CWD: {log_path.parent.resolve()}", log_contents)

    def test_run_patch_uses_gnu_dry_run_flag(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch(
                "arodnap.patch_tool.discover_patch_tool",
                return_value=PatchTool(
                    binary="/opt/homebrew/bin/gpatch",
                    flavor="gnu",
                    version="GNU patch 2.7.6",
                ),
            ):
                with patch(
                    "arodnap.patch_tool.run_command",
                    return_value=CommandResult(
                        command=("/opt/homebrew/bin/gpatch", "--dry-run"),
                        cwd=Path(temp_dir).resolve(),
                        returncode=0,
                        stdout="ok\n",
                        stderr="",
                    ),
                ):
                    execution = run_patch(
                        cwd=Path(temp_dir),
                        patch_path=Path(temp_dir) / "demo.patch",
                        strip_level=0,
                        check_only=True,
                        require_gnu=False,
                        operation_label="apply dry-run validation",
                    )

        self.assertIn("--dry-run", execution.command)
        self.assertNotIn("-C", execution.command)

    def test_run_patch_uses_bsd_dry_run_flag(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch(
                "arodnap.patch_tool.discover_patch_tool",
                return_value=PatchTool(
                    binary="/usr/bin/patch",
                    flavor="bsd",
                    version="patch 2.0-12u11-Apple",
                ),
            ):
                with patch(
                    "arodnap.patch_tool.run_command",
                    return_value=CommandResult(
                        command=("/usr/bin/patch", "-C"),
                        cwd=Path(temp_dir).resolve(),
                        returncode=0,
                        stdout="ok\n",
                        stderr="",
                    ),
                ):
                    execution = run_patch(
                        cwd=Path(temp_dir),
                        patch_path=Path(temp_dir) / "demo.patch",
                        strip_level=0,
                        check_only=True,
                        require_gnu=False,
                        operation_label="apply dry-run validation",
                    )

        self.assertIn("-C", execution.command)
        self.assertNotIn("--dry-run", execution.command)

    def test_run_patch_wraps_command_start_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch(
                "arodnap.patch_tool.discover_patch_tool",
                return_value=PatchTool(
                    binary="/opt/homebrew/bin/gpatch",
                    flavor="gnu",
                    version="GNU patch 2.7.6",
                ),
            ):
                with patch(
                    "arodnap.patch_tool.run_command",
                    side_effect=CommandExecutionError(
                        command=("/opt/homebrew/bin/gpatch", "-p", "0"),
                        cwd=Path(temp_dir).resolve(),
                        cause=OSError("boom"),
                    ),
                ):
                    with self.assertRaisesRegex(PatchToolError, "Failed to execute command"):
                        run_patch(
                            cwd=Path(temp_dir),
                            patch_path=Path(temp_dir) / "demo.patch",
                            strip_level=0,
                            check_only=False,
                            require_gnu=False,
                            operation_label="apply",
                        )

    def _fake_version_run(self, command, **kwargs):
        binary = Path(command[0]).name
        if binary == "gpatch":
            return CommandResult(
                command=tuple(command),
                cwd=kwargs.get("cwd"),
                returncode=0,
                stdout="GNU patch 2.7.6\n",
                stderr="",
            )
        if binary == "patch":
            return CommandResult(
                command=tuple(command),
                cwd=kwargs.get("cwd"),
                returncode=0,
                stdout="GNU patch 2.5.9\n",
                stderr="",
            )
        raise AssertionError(f"Unexpected version probe: {command}")


if __name__ == "__main__":
    unittest.main()
