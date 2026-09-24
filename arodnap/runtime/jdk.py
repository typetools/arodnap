from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache
import os
from pathlib import Path
import re
import shutil

from .commands import run_command
from .errors import CommandExecutionError

JAVA_HOME_ENV = "JAVA_HOME"
# RLFixer is built on WALA 1.8, which needs Java 17 or newer.
RLFIXER_MIN_JDK_MAJOR = 17

_JAVA_HOME_PROPERTY = re.compile(r"^\s*java\.home\s*=\s*(?P<path>.+?)\s*$", re.MULTILINE)
_VERSION_PROPERTY = re.compile(r"^\s*java\.specification\.version\s*=\s*(?P<version>\S+)\s*$", re.MULTILINE)


@dataclass(frozen=True)
class Jdk:
    home: Path
    major_version: int
    source: str

    @property
    def java(self) -> Path:
        return self.home / "bin" / "java"


class JdkResolutionError(RuntimeError):
    pass


def javac_language_options(release: int | None, encoding: str | None) -> list[str]:
    """The build's `--release` and `-encoding`, as javac options for Arodnap's own compiles."""
    options: list[str] = []
    if release is not None:
        options += ["--release", str(release)]
    if encoding:
        options += ["-encoding", encoding]
    return options


def resolve_jdk(env: dict[str, str] | None = None) -> Jdk:
    """Find the JDK Arodnap should use: JAVA_HOME if set, else the `java` on PATH."""
    environ = os.environ if env is None else env
    java_home = environ.get(JAVA_HOME_ENV)
    if java_home:
        java = Path(java_home) / "bin" / "java"
        if not java.is_file():
            raise JdkResolutionError(f"JAVA_HOME does not point at a JDK (missing {java}).")
        described = _describe(java, source="JAVA_HOME")
        return Jdk(home=Path(java_home), major_version=described.major_version, source=described.source)

    java_on_path = shutil.which("java", path=environ.get("PATH"))
    if java_on_path is None:
        raise JdkResolutionError("No JDK found. Install a JDK or set JAVA_HOME.")
    return _describe(Path(java_on_path), source="PATH")


def _describe(java: Path, *, source: str) -> Jdk:
    try:
        result = run_command([str(java), "-XshowSettings:properties", "-version"])
    except CommandExecutionError as exc:
        raise JdkResolutionError(f"Could not run {java}: {exc}") from exc

    # -XshowSettings writes to stderr.
    output = result.stderr + result.stdout
    home_match = _JAVA_HOME_PROPERTY.search(output)
    version_match = _VERSION_PROPERTY.search(output)
    if result.returncode != 0 or home_match is None or version_match is None:
        raise JdkResolutionError(f"Could not determine the JDK behind {java}.")

    version = version_match.group("version")
    major = int(version.split(".")[1] if version.startswith("1.") else version.split(".")[0])
    home = Path(home_match.group("path"))
    # Java 8 reports the embedded JRE; the JDK is its parent.
    if home.name == "jre" and (home.parent / "bin" / "javac").is_file():
        home = home.parent
    return Jdk(home=home, major_version=major, source=source)


@lru_cache(maxsize=1)
def java_executable() -> str:
    """The java launcher for Arodnap's Java stage tools; falls back to PATH lookup."""
    try:
        return str(resolve_jdk().java)
    except JdkResolutionError:
        return "java"
