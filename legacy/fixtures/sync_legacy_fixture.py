#!/usr/bin/env python3
"""
Synchronize the legacy normalized fixture from the Gradle baseline fixture.

This script keeps the legacy `src/lib/info/jarfile` fixture aligned with the
Gradle fixture so the old pipeline can still be exercised during migration.
"""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
FIXTURES_ROOT = REPO_ROOT / "test-projects"
DEFAULT_SOURCE = FIXTURES_ROOT / "gradle-pipeline-baseline"
DEFAULT_TARGET = REPO_ROOT / "legacy" / "fixtures" / "legacy-pipeline-baseline"
CHECKER_QUAL = REPO_ROOT / "legacy" / "checker-framework-3.49.0" / "checker" / "dist" / "checker-qual.jar"


def remove_path(path: Path) -> None:
    if not path.exists():
        return
    if path.is_dir() and not path.is_symlink():
        shutil.rmtree(path)
    else:
        path.unlink()


def write_lines(path: Path, lines: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n")


def collect_java_files(src_root: Path) -> list[Path]:
    return sorted(src_root.rglob("*.java"))


def sync_fixture(source_fixture: Path, target_fixture: Path) -> None:
    source_src_root = source_fixture / "src" / "main" / "java"
    target_src_root = target_fixture / "src"
    info_dir = target_fixture / "info"
    lib_dir = target_fixture / "lib"
    jarfile_dir = target_fixture / "jarfile"

    if not source_src_root.is_dir():
        raise FileNotFoundError(f"Gradle fixture source root not found: {source_src_root}")
    if not CHECKER_QUAL.is_file():
        raise FileNotFoundError(f"checker-qual.jar not found: {CHECKER_QUAL}")

    # Clean generated legacy-pipeline outputs so the fixture stays reusable.
    generated = [
        target_fixture / "baseline.log",
        target_fixture / "cf_classes",
        target_fixture / "classes",
        target_fixture / "compiled_classes",
        target_fixture / "src-files.txt",
        target_fixture / "wpi-iterations",
        target_fixture / "rlc-inference-log.txt",
        target_fixture / "ep-log.txt",
    ]
    for path in generated:
        remove_path(path)
    for jar in jarfile_dir.glob("*.jar"):
        remove_path(jar)
    for patch in target_src_root.rglob("*.patch"):
        remove_path(patch)

    remove_path(target_src_root)
    shutil.copytree(source_src_root, target_src_root)

    java_files = collect_java_files(target_src_root)
    sources = [f"src/{path.relative_to(target_src_root).as_posix()}" for path in java_files]
    classes = [
        str(path.relative_to(target_src_root)).replace("/", ".").replace(".java", "")
        for path in java_files
    ]

    write_lines(info_dir / "sources", sources)
    write_lines(info_dir / "classes", classes)

    lib_dir.mkdir(parents=True, exist_ok=True)
    jarfile_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(CHECKER_QUAL, lib_dir / "checker-qual.jar")

    gitkeep = jarfile_dir / ".gitkeep"
    if not gitkeep.exists():
        gitkeep.write_text("")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Sync the legacy normalized fixture from a Gradle fixture."
    )
    parser.add_argument(
        "--source",
        default=str(DEFAULT_SOURCE),
        help="Path to the Gradle fixture root.",
    )
    parser.add_argument(
        "--target",
        default=str(DEFAULT_TARGET),
        help="Path to the normalized legacy fixture root.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    sync_fixture(Path(args.source).resolve(), Path(args.target).resolve())


if __name__ == "__main__":
    main()
