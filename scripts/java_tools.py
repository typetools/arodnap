"""Builds Arodnap's Java tools from source and checks the committed jars against the build.

    python scripts/java_tools.py build           # mvn clean verify in every tool project
    python scripts/java_tools.py build --copy    # ... and copy the jars into prebuilt_plugin_jars/
    python scripts/java_tools.py check           # fail if a committed jar differs from its build

Build with JDK 21: jar contents depend on the javac version (lambda names, for example), so
`check` only matches jars built with the same JDK release. The manifest and Maven metadata
(`META-INF/MANIFEST.MF`, `META-INF/maven/`) record the build JDK and pom text, not code, and
are not compared.
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
PREBUILT = REPO / "restructure_plugins" / "prebuilt_plugin_jars"

# Maven project -> the jar it builds, as committed in prebuilt_plugin_jars/.
PROJECTS = {
    "restructure_plugins/AntCapture": "arodnap-ant-capture.jar",
    "restructure_plugins/MavenCapture": "arodnap-maven-capture.jar",
    "restructure_plugins/FieldTransformations": "arodnap-field-transformations.jar",
    "restructure_plugins/AutoCloseInjector": "AutoCloseInjector-1.0-SNAPSHOT.jar",
    "restructure_plugins/OwningFieldFixer": "OwningFieldFixer-1.0-SNAPSHOT.jar",
    "restructure_plugins/RLPatcher": "RLPatcher-1.0-SNAPSHOT.jar",
    "rlfixer/wala": "RLFixer-1.0-SNAPSHOT.jar",
}


def build(copy: bool) -> int:
    mvn = shutil.which("mvn")
    if mvn is None:
        print("mvn is not on PATH", file=sys.stderr)
        return 2
    for project, jar in PROJECTS.items():
        print(f"== {project}", flush=True)
        completed = subprocess.run([mvn, "-B", "-ntp", "clean", "verify"], cwd=REPO / project)
        if completed.returncode != 0:
            print(f"build failed: {project}", file=sys.stderr)
            return completed.returncode
        if copy:
            if _differing(REPO / project / "target" / jar, PREBUILT / jar):
                shutil.copy2(REPO / project / "target" / jar, PREBUILT / jar)
                print(f"copied {jar} into {PREBUILT.relative_to(REPO)}")
            else:
                print(f"{jar} is unchanged; kept the committed copy")
    return 0


def _code_entries(archive: zipfile.ZipFile) -> dict[str, bytes]:
    return {
        name: archive.read(name)
        for name in archive.namelist()
        if not name.endswith("/") and name != "META-INF/MANIFEST.MF" and not name.startswith("META-INF/maven/")
    }


def _differing(built_path: Path, committed_path: Path) -> list[str]:
    """Entries whose code differs between two builds of a jar (added, removed or changed)."""
    if not committed_path.is_file():
        return ["(no committed jar)"]
    with zipfile.ZipFile(built_path) as built_jar, zipfile.ZipFile(committed_path) as committed_jar:
        built, committed = _code_entries(built_jar), _code_entries(committed_jar)
    changed = {name for name in set(built) & set(committed) if built[name] != committed[name]}
    return sorted(set(built) ^ set(committed) | changed)


def check() -> int:
    stale = []
    for project, jar in PROJECTS.items():
        built_path = REPO / project / "target" / jar
        if not built_path.is_file():
            print(f"{project}: {built_path.relative_to(REPO)} is missing; run `build` first", file=sys.stderr)
            return 2
        differing = _differing(built_path, PREBUILT / jar)
        if differing:
            stale.append(jar)
            shown = ", ".join(differing[:5]) + (f" and {len(differing) - 5} more" if len(differing) > 5 else "")
            print(f"{jar}: differs from its source build in {shown}")
        else:
            print(f"{jar}: up to date")
    if stale:
        print(
            "\nThe committed jars do not match the source. Rebuild them with JDK 21:\n"
            "    python scripts/java_tools.py build --copy",
            file=sys.stderr,
        )
        return 1
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    build_parser = commands.add_parser("build", help="build and test every Java tool")
    build_parser.add_argument("--copy", action="store_true", help="copy the built jars into prebuilt_plugin_jars/")
    commands.add_parser("check", help="compare the committed jars with the built ones")
    args = parser.parse_args()
    return build(args.copy) if args.command == "build" else check()


if __name__ == "__main__":
    sys.exit(main())
