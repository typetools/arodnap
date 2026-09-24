import unittest
from pathlib import Path

from arodnap.main import main

try:
    import tomllib
except ModuleNotFoundError:  # Python 3.10
    tomllib = None


REPO_ROOT = Path(__file__).resolve().parents[1]


class PackagingTest(unittest.TestCase):
    @unittest.skipIf(tomllib is None, "tomllib needs Python 3.11")
    def test_pyproject_declares_console_entrypoint(self) -> None:
        pyproject_path = REPO_ROOT / "pyproject.toml"
        self.assertTrue(pyproject_path.is_file())

        payload = tomllib.loads(pyproject_path.read_text())
        self.assertEqual(payload["build-system"]["build-backend"], "setuptools.build_meta")
        self.assertEqual(payload["project"]["name"], "arodnap")
        self.assertEqual(payload["project"]["scripts"]["arodnap"], "arodnap.main:main")
        self.assertIn("version", payload["project"]["dynamic"])
        self.assertEqual(
            payload["tool"]["setuptools"]["dynamic"]["version"]["attr"],
            "arodnap.version.__version__",
        )

    def test_console_entrypoint_target_is_main_callable(self) -> None:
        self.assertTrue(callable(main))


if __name__ == "__main__":
    unittest.main()
