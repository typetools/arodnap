"""The short summary `arodnap repair` prints when it finishes."""

from __future__ import annotations

from pathlib import Path
import shlex
from typing import Any


def format_summary(
    leaks: dict[str, Any],
    *,
    repo_root: Path,
    patch_path: Path | None,
    patch_dir: Path | None,
    changed_files: int,
    html_path: Path | None,
) -> str:
    summary = leaks["summary"]
    total = summary["fixed"] + summary["remaining"]
    exposed = (
        f" ({summary['found_during_repair']} exposed by an earlier repair step)" if summary["found_during_repair"] else ""
    )
    lines = [f"Resource leaks: {total} found{exposed}, {summary['fixed']} fixed, {summary['remaining']} remaining"]
    if summary["remaining"]:
        width = len(str(max(summary["remaining_by_reason"].values(), default=0)))
        lines.append("  Remaining because:")
        for code, count in summary["remaining_by_reason"].items():
            lines.append(f"    {count:>{width}}  {leaks['reasons'].get(code, code)}")
    others = summary["other_checker_warnings"] + summary["javac_warnings"]
    if others:
        lines.append(f"  Also reported: {others} other warning(s) that are not resource leaks (see the report)")
    if patch_path is not None and changed_files:
        lines.append(f"Patch:  {patch_path} ({changed_files} file(s))")
        if patch_dir is not None:
            lines.append(
                f"Apply:  arodnap apply --patch-dir {shlex.quote(str(patch_dir))} {shlex.quote(str(repo_root))}"
            )
    else:
        lines.append("No changes to apply.")
    if html_path is not None:
        lines.append(f"Report: {html_path}")
    return "\n".join(lines)
