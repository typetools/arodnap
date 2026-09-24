import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import subprocess

import os
import time

from arodnap.runtime import (
    CommandExecutionError,
    CommandResult,
    CommandTimeoutError,
    environment_with_overrides,
    render_command_log,
    run_command,
)


class RuntimeCommandsTest(unittest.TestCase):
    def test_run_command_captures_process_result(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            cwd = Path(temp_dir)
            completed = subprocess.CompletedProcess(
                args=["echo", "hello"],
                returncode=0,
                stdout="out\n",
                stderr="err\n",
            )

            with patch("arodnap.runtime.commands.subprocess.run", return_value=completed) as run_mock:
                result = run_command(["echo", "hello"], cwd=cwd, env={"A": "B"})

            self.assertEqual(result.command, ("echo", "hello"))
            self.assertEqual(result.cwd, cwd.resolve())
            self.assertEqual(result.returncode, 0)
            self.assertEqual(result.stdout, "out\n")
            self.assertEqual(result.stderr, "err\n")
            self.assertEqual(run_mock.call_args.args[0], ["echo", "hello"])
            self.assertEqual(run_mock.call_args.kwargs["cwd"], cwd.resolve())
            self.assertEqual(run_mock.call_args.kwargs["env"], {"A": "B"})
            self.assertTrue(run_mock.call_args.kwargs["capture_output"])
            self.assertTrue(run_mock.call_args.kwargs["text"])
            self.assertFalse(run_mock.call_args.kwargs["check"])

    def test_render_command_log_uses_shared_format(self) -> None:
        result = CommandResult(
            command=("echo", "hello"),
            cwd=Path("/tmp/demo"),
            returncode=7,
            stdout="out\n",
            stderr="err\n",
        )

        self.assertEqual(
            render_command_log(result, tool_name="demo", timeout_seconds=15),
            "\n".join(
                [
                    "TOOL: demo",
                    "CWD: /tmp/demo",
                    "TIMEOUT_SECONDS: 15",
                    "COMMAND: echo hello",
                    "EXIT_CODE: 7",
                    "STDOUT:",
                    "out\n",
                    "STDERR:",
                    "err\n",
                ]
            ),
        )

    def test_run_command_wraps_process_start_failures(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            cwd = Path(temp_dir)
            with patch(
                "arodnap.runtime.commands.subprocess.run",
                side_effect=OSError("boom"),
            ):
                with self.assertRaisesRegex(CommandExecutionError, "Failed to execute command"):
                    run_command(["echo", "hello"], cwd=cwd)

    def test_commands_within_the_limit_run_normally(self) -> None:
        result = run_command(["sh", "-c", "echo out; echo err >&2; exit 3"], timeout_seconds=30)
        self.assertEqual((result.returncode, result.stdout, result.stderr), (3, "out\n", "err\n"))

    @unittest.skipUnless(os.name == "posix", "process groups are POSIX")
    def test_a_command_over_its_limit_is_killed_with_its_children(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            pid_file = Path(temp_dir) / "child.pid"
            # A build-like command that leaves a child process (a daemon) running.
            script = f"sleep 60 & echo $! > {pid_file}; echo started; wait"
            started = time.monotonic()
            with self.assertRaises(CommandTimeoutError) as raised:
                run_command(["sh", "-c", script], timeout_seconds=1)
            self.assertLess(time.monotonic() - started, 10)
            self.assertEqual(raised.exception.timeout_seconds, 1)
            self.assertIn("started", raised.exception.stdout)
            self.assertIn("timed out after 1 seconds", str(raised.exception))

            child = int(pid_file.read_text())
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline and _alive(child):
                time.sleep(0.05)
            self.assertFalse(_alive(child), "the child process must be killed too")

    def test_no_timeout_line_is_logged_without_a_limit(self) -> None:
        result = CommandResult(command=("echo",), cwd=None, returncode=0, stdout="", stderr="")
        self.assertNotIn("TIMEOUT_SECONDS", render_command_log(result, timeout_seconds=None))

    def test_environment_with_overrides_merges_and_removes_values(self) -> None:
        with patch.dict("os.environ", {"KEEP": "yes", "REMOVE": "gone"}, clear=True):
            env = environment_with_overrides({"KEEP": "still", "ADD": "new", "REMOVE": None})

        self.assertEqual(env["KEEP"], "still")
        self.assertEqual(env["ADD"], "new")
        self.assertNotIn("REMOVE", env)


def _alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    return True


if __name__ == "__main__":
    unittest.main()
