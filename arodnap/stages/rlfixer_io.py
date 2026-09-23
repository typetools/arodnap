"""Readers and writers for RLFixer's input and output formats.

Ported from the legacy RLFixerRunner.py / RLPatcherRunner.py scripts. The formats are
unchanged; file paths are now expressed relative to the adapter-discovered source root
instead of being derived from a `/src/` path convention.
"""

from __future__ import annotations

from dataclasses import dataclass
import json
from pathlib import Path
import re

_WARNING_HEADER = re.compile(r"^(?P<path>/.+?):(?P<line>\d+):\s+warning:", re.MULTILINE)
_REQUIRED_METHOD_NOT_CALLED = "(required.method.not.called)"
_OWNING_FIELD_OVERWRITE = "Non-final owning field might be overwritten"
_FIX_BLOCK = re.compile(
    r"""
    ^\s*(?P<number>\d+)\]\s*
    (?P<filepath>.+?);\s*Line\s+number\s+(?P<line_number>\d+)\s*\n
    (?P<suggestion>.*?)
    (?=^\s*-{10,}\s*$|\Z)
    """,
    re.MULTILINE | re.DOTALL | re.VERBOSE,
)
SOURCE_LEVEL_FIXES_MARKER = "SOURCE LEVEL FIXES"


@dataclass(frozen=True)
class CheckerWarning:
    filepath: str
    line_number: int
    message: str


@dataclass(frozen=True)
class FixSuggestion:
    filepath: str
    relpath: str
    line_number: int
    suggestion: str


def parse_checker_warnings(diagnostics_text: str) -> list[CheckerWarning]:
    """Split Checker Framework output into warning blocks that start with `/abs/File.java:<line>: warning:`."""
    matches = list(_WARNING_HEADER.finditer(diagnostics_text))
    warnings = []
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(diagnostics_text)
        warnings.append(
            CheckerWarning(
                filepath=match.group("path"),
                line_number=int(match.group("line")),
                message=diagnostics_text[match.start() : end].strip(),
            )
        )
    return warnings


def rlfixer_warnings_argument(
    warnings: list[CheckerWarning],
    *,
    source_root: Path,
) -> tuple[str, list[CheckerWarning]]:
    """Encode leak warnings as RLFixer's `-warnings` value: `relpath,line,method,isOwningOverwrite#...`.

    Returns the encoded string and the warnings that could not be expressed relative to
    the source root (and were therefore skipped).
    """
    entries = []
    skipped = []
    for warning in warnings:
        if _REQUIRED_METHOD_NOT_CALLED not in warning.message.splitlines()[0]:
            continue
        relpath = _relative_to(warning.filepath, source_root)
        if relpath is None:
            skipped.append(warning)
            continue
        owning_overwrite = _OWNING_FIELD_OVERWRITE in warning.message
        entries.append(f"{relpath},{warning.line_number},None,{owning_overwrite}")
    return ("#".join(entries) + "#" if entries else ""), skipped


def parse_fix_suggestions(fixes_text: str, *, source_root: Path) -> list[FixSuggestion]:
    suggestions = []
    for match in _FIX_BLOCK.finditer(fixes_text):
        filepath = match.group("filepath").strip()
        suggestions.append(
            FixSuggestion(
                filepath=filepath,
                relpath=_relative_to(filepath, source_root) or Path(filepath).name,
                line_number=int(match.group("line_number")),
                suggestion=match.group("suggestion").strip(),
            )
        )
    return suggestions


def parse_debug_fixable(debug_text: str) -> set[tuple[str, int]]:
    """Return (relpath, line) keys of warnings RLFixer matched and considers fixable.

    An empty set means the debug table was missing or unreadable, in which case callers
    treat every suggestion as a candidate.
    """
    lines = [line.strip() for line in debug_text.splitlines() if line.strip()]
    if not lines or "^" not in lines[0]:
        return set()

    columns = {name: index for index, name in enumerate(lines[0].split("^"))}
    required = ("Source File", "Line Number", "Matched Method", "Duplicate", "Unfixable")
    if any(name not in columns for name in required):
        return set()

    fixable: set[tuple[str, int]] = set()
    for row in lines[1:]:
        parts = row.split("^")
        if len(parts) < len(columns):
            continue
        try:
            line_number = int(parts[columns["Line Number"]].strip())
        except ValueError:
            continue
        duplicate = parts[columns["Duplicate"]].strip().lower()
        unfixable = parts[columns["Unfixable"]].strip().lower()
        if parts[columns["Matched Method"]].strip().upper() == "UNMATCHED":
            continue
        if duplicate != "false" or unfixable != "false":
            continue
        fixable.add((parts[columns["Source File"]].strip().replace("\\", "/"), line_number))
    return fixable


def select_fixable_suggestions(
    suggestions: list[FixSuggestion],
    fixable_keys: set[tuple[str, int]],
) -> list[FixSuggestion]:
    if not fixable_keys:
        return list(suggestions)
    return [item for item in suggestions if (item.relpath, item.line_number) in fixable_keys]


def match_fixes_to_warnings(
    suggestions: list[FixSuggestion],
    warnings: list[CheckerWarning],
) -> list[tuple[FixSuggestion, CheckerWarning]]:
    """Pair each suggestion with the warning at the same absolute path and line."""
    by_location = {(warning.filepath, warning.line_number): warning for warning in warnings}
    return [
        (suggestion, by_location[(suggestion.filepath, suggestion.line_number)])
        for suggestion in suggestions
        if (suggestion.filepath, suggestion.line_number) in by_location
    ]


def build_rlpatcher_prompt(warning: CheckerWarning, suggestion: FixSuggestion) -> str:
    """JSON input for RLPatcher's PromptParser."""
    return json.dumps(
        {"CF Leaks": [warning.message], "RLFixer hint": [suggestion.suggestion]},
        indent=2,
    )


def _relative_to(filepath: str, root: Path) -> str | None:
    try:
        return Path(filepath).resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return None


__all__ = [
    "CheckerWarning",
    "FixSuggestion",
    "SOURCE_LEVEL_FIXES_MARKER",
    "build_rlpatcher_prompt",
    "match_fixes_to_warnings",
    "parse_checker_warnings",
    "parse_debug_fixable",
    "parse_fix_suggestions",
    "rlfixer_warnings_argument",
    "select_fixable_suggestions",
]
