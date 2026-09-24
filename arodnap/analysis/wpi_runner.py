"""Whole-program inference for the Resource Leak Checker.

WPI is the checker's `-Ainfer=ajava` mode run to a fixpoint: each iteration compiles the
sources with the previous iteration's inferred `.ajava` files and stops when an iteration
infers nothing new. This is the loop the Checker Framework's `wpi.sh` delegates to
do-like-javac (`do_like_javac/tools/wpi.py`); Arodnap runs it directly on the sources
and classpath it already has, so it does not rebuild the project on every iteration or
depend on `wpi.sh`'s JDK and Python requirements.

Mirrored from do-like-javac's WPI tool as shipped with Checker Framework 4.2.3. When the
bundled Checker Framework is upgraded, `tests/test_wpi_upstream_parity.py` fails if that
tool's inference flags change, so this loop can be reviewed against it.
"""

from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path
import re
import shutil
import tempfile

from arodnap.contracts import RunConfig
from arodnap.runtime import CommandExecutionError, Jdk, render_command_log, run_command

from .checker_framework import (
    RESOURCE_LEAK_CHECKER,
    CheckerFrameworkError,
    checker_javac_command,
    resolve_analysis_jdk,
)

# Flags do-like-javac adds to every WPI iteration (tools/wpi.py). The previous iteration's
# output is passed with -Aajava. `-AsuppressWarnings=type.anno.before.modifier` is only
# added there for delombok'd sources, which Arodnap does not produce.
WPI_ITERATION_FLAGS = ("-Ainfer=ajava", "-Awarns")
MAX_WPI_ITERATIONS = 20
# The Checker Framework cannot write an .ajava file for a source whose comments contain a
# Unicode-escaped lone surrogate (e.g. "\uD800" in Javadoc): it escapes them only in literals.
# That class then gets no inferred annotations; the rest of the program is unaffected.
_AJAVA_WRITE_FAILURE = re.compile(r"^error: Error while writing ajava file (\S+)", re.MULTILINE)
_COMPILER_ERROR = re.compile(r"(^|: )error: ", re.MULTILINE)


@dataclass(frozen=True)
class WpiRunResult:
    log_path: Path
    inference_dir: Path
    iterations: int
    # .ajava files the Checker Framework could not write (their classes lack inferred annotations)
    incomplete: tuple[str, ...] = ()


class WpiRunError(RuntimeError):
    pass


def run_wpi(
    config: RunConfig,
    *,
    workspace_root: Path,
    source_files_file: Path,
    classpath_entries_file: Path,
    log_path: Path,
    inference_root: Path,
) -> WpiRunResult:
    log_path = log_path.resolve()
    inference_root = inference_root.resolve()
    try:
        jdk = resolve_analysis_jdk(config.cf_root)
    except CheckerFrameworkError as exc:
        raise WpiRunError(str(exc)) from exc
    classpath = os.pathsep.join(
        line.strip() for line in classpath_entries_file.read_text().splitlines() if line.strip()
    )

    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_path.write_text(
        f"TOOL: wpi\nWORKSPACE: {workspace_root.resolve()}\n"
        f"JDK: {jdk.home} ({jdk.major_version}, from {jdk.source})\n"
    )

    with tempfile.TemporaryDirectory(prefix="arodnap-wpi-") as temp_dir:
        temp_root = Path(temp_dir)
        # javac writes -Ainfer output to <working directory>/build/whole-program-inference.
        javac_cwd = temp_root / "cwd"
        javac_cwd.mkdir()
        generated_dir = javac_cwd / "build" / "whole-program-inference"
        previous: Path | None = None
        incomplete: set[str] = set()

        for iteration in range(1, MAX_WPI_ITERATIONS + 1):
            shutil.rmtree(generated_dir, ignore_errors=True)
            incomplete |= _run_iteration(
                config,
                jdk=jdk,
                classpath=classpath,
                source_files_file=source_files_file.resolve(),
                previous=previous,
                javac_cwd=javac_cwd,
                classes_dir=temp_root / f"classes{iteration}",
                log_path=log_path,
                iteration=iteration,
            )
            current = temp_root / f"iteration{iteration}"
            if generated_dir.is_dir():
                shutil.move(str(generated_dir), current)
            else:
                current.mkdir()
            if previous is not None and _same_tree(previous, current):
                break
            previous = current
        else:
            raise WpiRunError(
                f"WPI did not reach a fixpoint after {MAX_WPI_ITERATIONS} iterations. See log: {log_path}"
            )

        with log_path.open("a", encoding="utf-8") as handle:
            handle.write(f"\nFIXPOINT_AFTER_ITERATIONS: {iteration}\n")
            for ajava in sorted(incomplete):
                handle.write(f"INCOMPLETE_INFERENCE: {ajava}\n")
        if inference_root.exists():
            shutil.rmtree(inference_root)
        shutil.copytree(current, inference_root)

    return WpiRunResult(
        log_path=log_path,
        inference_dir=inference_root,
        iterations=iteration,
        incomplete=tuple(sorted(incomplete)),
    )


def _run_iteration(
    config: RunConfig,
    *,
    jdk: Jdk,
    classpath: str,
    source_files_file: Path,
    previous: Path | None,
    javac_cwd: Path,
    classes_dir: Path,
    log_path: Path,
    iteration: int,
) -> set[str]:
    """Run one iteration; return the .ajava files the Checker Framework could not write."""
    classes_dir.mkdir()
    try:
        command = [
            *checker_javac_command(config.cf_root, jdk),
            "-processor",
            RESOURCE_LEAK_CHECKER,
            *WPI_ITERATION_FLAGS,
        ]
    except CheckerFrameworkError as exc:
        raise WpiRunError(str(exc)) from exc
    if previous is not None:
        command.append(f"-Aajava={previous}")
    command.extend(["-classpath", classpath, "-d", str(classes_dir), f"@{source_files_file}"])

    try:
        result = run_command(command, cwd=javac_cwd)
    except CommandExecutionError as exc:
        raise WpiRunError(str(exc)) from exc
    with log_path.open("a", encoding="utf-8") as handle:
        handle.write(f"\n== WPI iteration {iteration} ==\n")
        handle.write(render_command_log(result, timeout_seconds=config.timeouts.analysis_seconds))
    output = result.stdout + result.stderr
    ajava_failures = set(_AJAVA_WRITE_FAILURE.findall(output))
    other_errors = [
        line for line in output.splitlines()
        if _COMPILER_ERROR.search(line) and not _AJAVA_WRITE_FAILURE.match(line)
    ]
    if result.returncode != 0 and (other_errors or not ajava_failures):
        raise WpiRunError(f"WPI iteration {iteration} failed to compile. See log: {log_path}")
    return ajava_failures


def _same_tree(left: Path, right: Path) -> bool:
    left_files = {path.relative_to(left): path for path in left.rglob("*") if path.is_file()}
    right_files = {path.relative_to(right): path for path in right.rglob("*") if path.is_file()}
    if left_files.keys() != right_files.keys():
        return False
    return all(left_files[name].read_bytes() == right_files[name].read_bytes() for name in left_files)


__all__ = [
    "MAX_WPI_ITERATIONS",
    "WPI_ITERATION_FLAGS",
    "WpiRunError",
    "WpiRunResult",
    "run_wpi",
]
