"""Where Arodnap's bundled tools live.

An installed package carries them in ``arodnap/_bundled`` (see ``setup.py``, which copies them
there when the package is built). In a source checkout they are read from the repository:
``restructure_plugins/prebuilt_plugin_jars``, ``checker_framework/checker-framework-<version>``
and ``checker_framework/stubs``.
"""

from __future__ import annotations

from pathlib import Path

CHECKER_FRAMEWORK_VERSION = "4.2.3"
CHECKER_FRAMEWORK_DIRNAME = f"checker-framework-{CHECKER_FRAMEWORK_VERSION}"

# Every jar the pipeline runs; the package build copies exactly these.
BUNDLED_JARS = (
    "AutoCloseInjector-1.0-SNAPSHOT.jar",
    "OwningFieldFixer-1.0-SNAPSHOT.jar",
    "RLFixer-1.0-SNAPSHOT.jar",
    "RLPatcher-1.0-SNAPSHOT.jar",
    "arodnap-ant-capture.jar",
    "arodnap-maven-capture.jar",
    "arodnap-field-transformations.jar",
    "error_prone_core-2.42.0-with-dependencies.jar",
    "error_prone_core-2.50.0-with-dependencies.jar",
    "dataflow-errorprone-3.41.0-eisop1.jar",
)

_PACKAGE = Path(__file__).resolve().parent
BUNDLED_ROOT = _PACKAGE / "_bundled"
_CHECKOUT = _PACKAGE.parent


def jars_dir() -> Path:
    bundled = BUNDLED_ROOT / "jars"
    return bundled if bundled.is_dir() else _CHECKOUT / "restructure_plugins" / "prebuilt_plugin_jars"


def checker_framework_dir() -> Path:
    bundled = BUNDLED_ROOT / CHECKER_FRAMEWORK_DIRNAME
    return bundled if bundled.is_dir() else _CHECKOUT / "checker_framework" / CHECKER_FRAMEWORK_DIRNAME


def stubs_dir() -> Path:
    bundled = BUNDLED_ROOT / "stubs"
    return bundled if bundled.is_dir() else _CHECKOUT / "checker_framework" / "stubs"


def jar(name: str) -> Path:
    return jars_dir() / name
