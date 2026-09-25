"""Applies unified diffs, so Arodnap needs no external `patch` program.

Behaves like GNU patch for the diffs Arodnap produces: each hunk is placed where its old lines
(context and removed lines) match exactly, at the nearest position to where the diff says, so
earlier edits that shift lines are fine. `fuzz` lets up to that many context lines at each end
of a hunk go unmatched, like `patch -F`. `ignore_whitespace` compares lines with whitespace
collapsed. Nothing is written unless every hunk of every file applies.

Only "\n" ends a line, and a file keeps its own line endings: lines are matched ignoring a
trailing "\r", context lines keep the file's bytes, and inserted lines get the file's ending
("\r\n" in a Windows-style file) whatever the patch has.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
import re

_HUNK_HEADER = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")
_NO_NEWLINE = "\\ No newline at end of file"
DEV_NULL = "/dev/null"


class PatchParseError(ValueError):
    pass


@dataclass
class Hunk:
    old_start: int
    old_count: int = 1
    lines: list[tuple[str, str]] = field(default_factory=list)  # (" " | "-" | "+", text with line ending)

    def anchor(self) -> int:
        """0-based index of the first old line; a hunk that removes nothing inserts after old_start."""
        return self.old_start if self.old_count == 0 else max(self.old_start - 1, 0)

    def old(self) -> list[str]:
        return [text for tag, text in self.lines if tag in " -"]

    def new(self) -> list[str]:
        return [text for tag, text in self.lines if tag in " +"]

    def context_edges(self) -> tuple[int, int]:
        leading = 0
        while leading < len(self.lines) and self.lines[leading][0] == " ":
            leading += 1
        trailing = 0
        while trailing < len(self.lines) - leading and self.lines[len(self.lines) - 1 - trailing][0] == " ":
            trailing += 1
        return leading, trailing


@dataclass
class FilePatch:
    old_path: str
    new_path: str
    hunks: list[Hunk] = field(default_factory=list)


@dataclass
class PatchOutcome:
    ok: bool
    patched: list[str]
    messages: list[str]
    errors: list[str]


def split_lines(text: str) -> list[str]:
    """Lines with their endings, split at "\n" only (unlike str.splitlines, which also splits at
    "\r", form feeds and other separators that can appear inside a line of source code)."""
    lines = text.split("\n")
    last = lines.pop()
    return [line + "\n" for line in lines] + ([last] if last else [])


def parse_unified_diff(text: str) -> list[FilePatch]:
    files: list[FilePatch] = []
    lines = split_lines(text)
    index = 0
    current: FilePatch | None = None
    hunk: Hunk | None = None  # the hunk still being read
    last: Hunk | None = None  # the hunk a "\ No newline" marker refers to
    old_left = new_left = 0
    while index < len(lines):
        raw = lines[index]
        bare = raw.rstrip("\r\n")
        index += 1
        if hunk is None and bare.startswith("--- ") and index < len(lines) and lines[index].startswith("+++ "):
            current = FilePatch(_header_path(bare), _header_path(lines[index].rstrip("\r\n")))
            files.append(current)
            index += 1
            last = None
            continue
        if bare == _NO_NEWLINE:
            if last is not None and last.lines:
                tag, previous = last.lines[-1]
                # Only the "\n" the diff added; a "\r" before it belongs to the line.
                last.lines[-1] = (tag, previous[:-1] if previous.endswith("\n") else previous)
            continue
        header = _HUNK_HEADER.match(bare) if hunk is None else None
        if header:
            if current is None:
                raise PatchParseError(f"hunk before any file header: {bare}")
            old_left = int(header.group(2)) if header.group(2) is not None else 1
            hunk = last = Hunk(old_start=int(header.group(1)), old_count=old_left)
            current.hunks.append(hunk)
            new_left = int(header.group(4)) if header.group(4) is not None else 1
        elif hunk is not None:
            tag = raw[:1] if raw[:1] in (" ", "-", "+") else " "  # a bare newline is an empty context line
            text = raw[1:] if raw[:1] in (" ", "-", "+") else raw
            hunk.lines.append((tag, text))
            old_left -= tag in " -"
            new_left -= tag in " +"
            if old_left < 0 or new_left < 0:
                raise PatchParseError("hunk is longer than its header says")
        if hunk is not None and old_left == 0 and new_left == 0:
            hunk = None
    if hunk is not None:
        raise PatchParseError("patch ends in the middle of a hunk")
    if not files:
        raise PatchParseError("no file headers (---/+++) found")
    return files


def apply_patch(
    root: Path,
    patch_text: str,
    *,
    strip_level: int = 0,
    check_only: bool = False,
    fuzz: int = 0,
    ignore_whitespace: bool = False,
) -> PatchOutcome:
    try:
        file_patches = parse_unified_diff(patch_text)
    except PatchParseError as exc:
        return PatchOutcome(False, [], [], [f"malformed patch: {exc}"])

    results: dict[Path, list[str] | None] = {}
    messages: list[str] = []
    errors: list[str] = []
    for file_patch in file_patches:
        creating = file_patch.old_path == DEV_NULL
        deleting = file_patch.new_path == DEV_NULL
        relative = _strip(file_patch.old_path if deleting else file_patch.new_path, strip_level)
        target = root / relative
        messages.append(f"{'checking' if check_only else 'patching'} file {relative}")
        if target in results:
            original = results[target] or []
        elif creating:
            if target.exists():
                errors.append(f"{relative}: the patch creates it, but it already exists")
                continue
            original = []
        elif target.is_file():
            original = split_lines(target.read_bytes().decode("utf-8", errors="surrogateescape"))
        else:
            errors.append(f"can't find file to patch: {relative}")
            continue
        patched, failures = _apply_hunks(original, file_patch.hunks, fuzz=fuzz, ignore_whitespace=ignore_whitespace)
        if failures:
            errors.extend(f"{relative}: {failure}" for failure in failures)
            continue
        results[target] = None if deleting else patched

    if errors:
        return PatchOutcome(False, [], messages, errors)
    if not check_only:
        for target, content in results.items():
            if content is None:
                target.unlink()
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes("".join(content).encode("utf-8", errors="surrogateescape"))
    return PatchOutcome(True, [_relative(target, root) for target in results], messages, [])


def _apply_hunks(lines: list[str], hunks: list[Hunk], *, fuzz: int, ignore_whitespace: bool) -> tuple[list[str], list[str]]:
    result = list(lines)
    ending = _file_line_ending(lines)
    delta = 0  # how far the file has moved relative to the diff's line numbers
    floor = 0  # hunks apply in order and never overlap
    failures: list[str] = []
    for number, hunk in enumerate(hunks, start=1):
        expected = hunk.anchor() + delta
        placed = None
        leading, trailing = hunk.context_edges()
        for dropped in range(0, fuzz + 1):
            lead, trail = min(dropped, leading), min(dropped, trailing)
            end = len(hunk.lines) - trail
            old = [text for tag, text in hunk.lines[lead:end] if tag in " -"]
            position = _find(result, old, expected + lead, floor, ignore_whitespace)
            if position is not None:
                new = _replacement(hunk.lines[lead:end], result[position:position + len(old)], ending)
                placed = (position, old, new, lead)
                break
            if dropped >= max(leading, trailing):
                break
        if placed is None:
            already = _find(result, hunk.new(), expected, floor, ignore_whitespace) is not None and hunk.old() != hunk.new()
            failures.append(
                f"hunk #{number} FAILED at {hunk.old_start}"
                + (" (the change seems to be applied already)" if already else "")
            )
            continue
        position, old, new, lead = placed
        result[position:position + len(old)] = new
        # Lines after this hunk now sit this far from where the diff numbers them.
        delta = position - (hunk.anchor() + lead) + (len(new) - len(old))
        floor = position + len(new)
    return result, failures


def _replacement(hunk_lines: list[tuple[str, str]], matched: list[str], ending: str | None) -> list[str]:
    """The hunk's new lines: context lines as they are in the file, inserted lines with the
    file's line ending (or the patch's, when the file has no line endings to follow)."""
    new = []
    old_index = 0
    for tag, text in hunk_lines:
        if tag == " ":
            new.append(matched[old_index])
            old_index += 1
        elif tag == "-":
            old_index += 1
        else:
            new.append(text.rstrip("\r\n") + ending if ending and text.endswith("\n") else text)
    return new


def _file_line_ending(lines: list[str]) -> str | None:
    """The file's line ending, or None for a file without any (empty, or one unterminated line)."""
    ended = [line for line in lines if line.endswith("\n")]
    if not ended:
        return None
    crlf = sum(1 for line in ended if line.endswith("\r\n"))
    return "\r\n" if crlf > len(ended) - crlf else "\n"


def _find(lines: list[str], wanted: list[str], expected: int, floor: int, ignore_whitespace: bool) -> int | None:
    if not wanted:
        return max(min(expected, len(lines)), floor)
    last = len(lines) - len(wanted)
    if last < floor:
        return None
    normalize = _collapse if ignore_whitespace else _exact
    target = [normalize(line) for line in wanted]
    start = min(max(expected, floor), last)
    for distance in range(0, max(start - floor, last - start) + 1):
        for position in (start - distance, start + distance):
            if floor <= position <= last and all(
                normalize(lines[position + i]) == target[i] for i in range(len(target))
            ):
                return position
    return None


def _exact(line: str) -> str:
    return line.rstrip("\r\n") + ("\n" if line.endswith(("\n", "\r")) else "")


def _collapse(line: str) -> str:
    return " ".join(line.split())


def _header_path(line: str) -> str:
    path = line[4:].split("\t", 1)[0].strip()
    return path


def _strip(path: str, level: int) -> str:
    parts = path.split("/")
    return "/".join(parts[level:]) if level else path


def _relative(path: Path, root: Path) -> str:
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return str(path)


__all__ = ["PatchOutcome", "PatchParseError", "apply_patch", "parse_unified_diff"]
