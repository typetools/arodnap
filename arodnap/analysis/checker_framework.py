"""How Arodnap invokes a Checker Framework distribution.

Arodnap runs the checker through `checker/dist/checker.jar` on the JDK it resolved
(JAVA_HOME, else the `java` on PATH), instead of through the distribution's `bin/javac`
wrapper, which always uses the first `java` on PATH.
"""

from __future__ import annotations

from pathlib import Path
import re
import zipfile

from arodnap.runtime import RLFIXER_MIN_JDK_MAJOR, Jdk, JdkResolutionError, resolve_jdk

RESOURCE_LEAK_CHECKER = "org.checkerframework.checker.resourceleak.ResourceLeakChecker"
_CHECKER_CLASS_ENTRY = "org/checkerframework/checker/resourceleak/ResourceLeakChecker.class"
_WPI_SCRIPT_JAVA_VERSION = re.compile(r'"\$\{java_version\}" = (\d+) \]')


class CheckerFrameworkError(RuntimeError):
    pass


def checker_jar(cf_root: Path) -> Path:
    jar = cf_root / "checker" / "dist" / "checker.jar"
    if not jar.is_file():
        raise CheckerFrameworkError(f"Checker Framework jar not found: {jar}")
    return jar


def checker_javac_command(cf_root: Path, jdk: Jdk) -> list[str]:
    """The start of a javac command that runs with the Checker Framework on `jdk`."""
    return [str(jdk.java), "-jar", str(checker_jar(cf_root))]


def minimum_jdk_major(cf_root: Path) -> int:
    """Oldest JDK that can run this distribution, read from checker.jar's class file version."""
    try:
        with zipfile.ZipFile(checker_jar(cf_root)) as archive:
            header = archive.read(_CHECKER_CLASS_ENTRY)[:8]
    except (KeyError, OSError, zipfile.BadZipFile) as exc:
        raise CheckerFrameworkError(f"Cannot read the Resource Leak Checker from {cf_root}: {exc}") from exc
    return int.from_bytes(header[6:8], "big") - 44


def resolve_analysis_jdk(cf_root: Path) -> Jdk:
    """The JDK that runs the analysis: JAVA_HOME, else `java` on PATH.

    It must be new enough for both the Checker Framework distribution and RLFixer.
    """
    try:
        jdk = resolve_jdk()
    except JdkResolutionError as exc:
        raise CheckerFrameworkError(str(exc)) from exc
    minimum = max(minimum_jdk_major(cf_root), RLFIXER_MIN_JDK_MAJOR)
    if jdk.major_version < minimum:
        raise CheckerFrameworkError(
            f"Arodnap with {cf_root.name} needs JDK {minimum} or newer; found JDK {jdk.major_version} "
            f"at {jdk.home} (from {jdk.source}). Set JAVA_HOME to a newer JDK."
        )
    return jdk


def tested_jdk_majors(cf_root: Path) -> tuple[int, ...]:
    """JDKs the distribution's own wpi.sh lists, used only to warn about untested JDKs."""
    try:
        text = (cf_root / "checker" / "bin" / "wpi.sh").read_text()
    except OSError:
        return ()
    return tuple(sorted({int(major) for major in _WPI_SCRIPT_JAVA_VERSION.findall(text)}))


__all__ = [
    "CheckerFrameworkError",
    "RESOURCE_LEAK_CHECKER",
    "checker_jar",
    "checker_javac_command",
    "minimum_jdk_major",
    "resolve_analysis_jdk",
    "tested_jdk_majors",
]
