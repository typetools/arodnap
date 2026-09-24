"""Builds the arodnap package with the tools it runs inside it.

The Java tools, the Checker Framework distribution and its stubs live outside the Python package
in the repository (restructure_plugins/ and checker_framework/). When the package is built they
are copied into arodnap/_bundled, where arodnap.resources finds them at run time. Everything else
is configured in pyproject.toml.
"""

import shutil
from pathlib import Path

from setuptools import setup
from setuptools.command.build_py import build_py

ROOT = Path(__file__).resolve().parent


def _resources() -> dict:
    path = ROOT / "arodnap" / "resources.py"
    namespace: dict = {"__file__": str(path), "__name__": "arodnap_resources"}
    exec(path.read_text(), namespace)  # plain constants; importing arodnap is not possible during a build
    return namespace


class BuildWithBundledTools(build_py):
    def run(self) -> None:
        super().run()
        resources = _resources()
        bundled = Path(self.build_lib) / "arodnap" / "_bundled"
        shutil.rmtree(bundled, ignore_errors=True)
        (bundled / "jars").mkdir(parents=True)
        for name in resources["BUNDLED_JARS"]:
            shutil.copy2(ROOT / "restructure_plugins" / "prebuilt_plugin_jars" / name, bundled / "jars" / name)
        checker_framework = resources["CHECKER_FRAMEWORK_DIRNAME"]
        shutil.copytree(ROOT / "checker_framework" / checker_framework, bundled / checker_framework)
        shutil.copytree(ROOT / "checker_framework" / "stubs", bundled / "stubs")
        for notice in ("LICENSE", "THIRD_PARTY_NOTICES.md"):
            shutil.copy2(ROOT / notice, bundled / notice)


setup(cmdclass={"build_py": BuildWithBundledTools})
