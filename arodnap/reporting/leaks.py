"""Per-warning results of a repair run: which resource leaks were fixed, and why the
others were not.

Every analysis run (initial, after each source-changing stage, final) is parsed, and each
warning is followed from run to run. A warning is identified by its file, its Checker
Framework key and its structured `-Adetailedmsgtext` fields (resource expression, type,
finalizer); line numbers only order warnings that are otherwise identical, because the
patches shift lines. The stage after which a warning disappears is the one that fixed it.
For a leak that remains, the reason comes from RLFixer's debug table and RLPatcher's
per-suggestion outcomes.
"""

from __future__ import annotations

from dataclasses import dataclass, field
import json
from pathlib import Path
import re
from typing import Any, Sequence

from arodnap.contracts import ReanalyzeResult, StageResult
from arodnap.stages.rlfixer_io import parse_checker_warnings

LEAK_KEY = "required.method.not.called"
_KEY = re.compile(r"warning: [(\[](?:[\w.]+:)?([\w.]+)[)\]]")

# Why a leak remains, keyed by reason code. Codes are stable output; texts may be reworded.
REASONS = {
    "rlfixer_unmatched": "RLFixer could not find the leaking allocation in the compiled code",
    "rlfixer_duplicate": "RLFixer treats it as a duplicate of another warning",
    "rlfixer_unfixable": "RLFixer found no fix (for example, the resource is stored in a field or collection)",
    "no_suggestion": "RLFixer suggested no fix",
    "no_change": "nothing to change (for example, a returned resource whose callers are not in the project)",
    "unsafe": "a fix would have to reorder code, which could change behavior",
    "rejected": "the fix did not compile",
    "unsupported": "RLPatcher does not support this kind of fix",
    "crashed": "RLPatcher crashed on this fix",
    "timed_out": "RLPatcher exceeded --stage-timeout",
    "conflict": "its fix overlapped a fix applied earlier and was skipped",
    "fix_applied_warning_remains": "a fix was applied, but the checker still reports the leak",
    "appeared_after_fixes": "it appeared after RLPatcher's fixes (a fix exposed or moved it)",
    "not_analyzed_by_rlfixer": "it was not part of RLFixer's input",
}


@dataclass(frozen=True)
class Diagnostic:
    path: str  # relative to the workspace root when possible
    line: int
    key: str
    fields: tuple[str, ...]  # -Adetailedmsgtext arguments; empty for plain javac warnings
    text: str  # the full warning block

    @property
    def from_checker(self) -> bool:
        return bool(self.fields)

    def identity(self) -> tuple:
        if self.key == LEAK_KEY and len(self.fields) >= 3:
            # method, expression and type; the reason text changes when code around it changes.
            return (self.path, self.key, *self.fields[:3])
        if self.fields:
            return (self.path, self.key, *self.fields)
        return (self.path, self.key, self.text.splitlines()[0].split(f"[{self.key}]", 1)[-1].strip())


def parse_diagnostics(text: str, workspace_root: Path) -> list[Diagnostic]:
    diagnostics = []
    root = workspace_root.resolve()
    for warning in parse_checker_warnings(text):
        first_line = warning.message.splitlines()[0]
        key_match = _KEY.search(first_line)
        if key_match is None:
            continue
        parts = [part.strip() for part in first_line.split("$$")]
        # "<location> [key] $$ N $$ arg1 ... $$ argN $$ ( start, end ) $$ message"
        fields: tuple[str, ...] = ()
        if len(parts) >= 3 and parts[1].isdigit():
            count = int(parts[1])
            fields = tuple(parts[2 : 2 + count])
        diagnostics.append(
            Diagnostic(
                path=_relative(warning.filepath, root),
                line=warning.line_number,
                key=key_match.group(1),
                fields=fields,
                text=warning.message,
            )
        )
    return diagnostics


def match_runs(previous: Sequence[Diagnostic], current: Sequence[Diagnostic]) -> dict[int, int]:
    """Map each warning of `current` (by index) to the same warning in `previous`."""
    groups: dict[tuple, list[int]] = {}
    for index in sorted(range(len(previous)), key=lambda i: previous[i].line):
        groups.setdefault(previous[index].identity(), []).append(index)
    matched: dict[int, int] = {}
    for index in sorted(range(len(current)), key=lambda i: current[i].line):
        candidates = groups.get(current[index].identity())
        if candidates:
            matched[index] = candidates.pop(0)
    return matched


@dataclass
class TrackedWarning:
    first_seen: str
    diagnostic: Diagnostic
    runs: dict[str, Diagnostic] = field(default_factory=dict)
    fixed_by: str | None = None
    reason: str | None = None
    fix_patch: str | None = None


def build_leak_report(
    analyses: Sequence[tuple[str, ReanalyzeResult]],
    *,
    workspace_root: Path,
    stage_for_label: dict[str, str],
    rlfixer_label: str | None,
    stage_results: dict[str, StageResult],
) -> dict[str, Any]:
    """The `leaks` section of report.json."""
    runs = [
        (label, parse_diagnostics(result.diagnostics_path.read_text(errors="replace"), workspace_root))
        for label, result in analyses
    ]
    tracked: list[TrackedWarning] = []
    live: dict[int, TrackedWarning] = {}
    for position, (label, diagnostics) in enumerate(runs):
        matches = match_runs(runs[position - 1][1], diagnostics) if position else {}
        next_live: dict[int, TrackedWarning] = {}
        for index, diagnostic in enumerate(diagnostics):
            if index in matches:
                warning = live.pop(matches[index])
            else:
                warning = TrackedWarning(first_seen=label, diagnostic=diagnostic)
                tracked.append(warning)
            warning.runs[label] = diagnostic
            next_live[index] = warning
        for gone in live.values():
            gone.fixed_by = stage_for_label.get(label, label)
        live = next_live

    rlfixer_view = _RLFixerView.load(analyses, rlfixer_label, stage_results)
    for warning in tracked:
        if warning.diagnostic.key != LEAK_KEY:
            continue
        if warning.fixed_by is None:
            warning.reason = rlfixer_view.reason(warning, final_label=runs[-1][0])
        elif warning.fixed_by == "rlpatcher":
            warning.fix_patch = rlfixer_view.patch_for(warning)
        elif warning.fixed_by in stage_results:
            warning.fix_patch = stage_results[warning.fixed_by].artifacts.get("patch")

    leaks = [warning for warning in tracked if warning.diagnostic.key == LEAK_KEY]
    initial_label = runs[0][0]
    others = [
        warning for warning in tracked if warning.diagnostic.key != LEAK_KEY and runs[-1][0] in warning.runs
    ]
    reasons: dict[str, int] = {}
    for warning in leaks:
        if warning.reason:
            reasons[warning.reason] = reasons.get(warning.reason, 0) + 1
    fixed_by: dict[str, int] = {}
    for warning in leaks:
        if warning.fixed_by:
            fixed_by[warning.fixed_by] = fixed_by.get(warning.fixed_by, 0) + 1
    return {
        "summary": {
            "initial": sum(1 for warning in leaks if warning.first_seen == initial_label),
            "found_during_repair": sum(1 for warning in leaks if warning.first_seen != initial_label),
            "fixed": sum(1 for warning in leaks if warning.fixed_by),
            "remaining": sum(1 for warning in leaks if not warning.fixed_by),
            "fixed_by_stage": dict(sorted(fixed_by.items())),
            "remaining_by_reason": dict(sorted(reasons.items(), key=lambda item: (-item[1], item[0]))),
            "other_checker_warnings": sum(1 for warning in others if warning.diagnostic.from_checker),
            "javac_warnings": sum(1 for warning in others if not warning.diagnostic.from_checker),
        },
        "reasons": REASONS,
        "warnings": [_leak_payload(number, warning) for number, warning in enumerate(leaks, start=1)],
        "other_warnings": [
            {
                "file": warning.diagnostic.path,
                "line": warning.runs[runs[-1][0]].line,
                "kind": warning.diagnostic.key,
                "checker": warning.diagnostic.from_checker,
            }
            for warning in others
        ],
    }


def _leak_payload(number: int, warning: TrackedWarning) -> dict[str, Any]:
    first = warning.diagnostic
    fields = list(first.fields) + [""] * 4
    payload: dict[str, Any] = {
        "id": number,
        "file": first.path,
        "line": first.line,
        "first_seen": warning.first_seen,
        "finalizer": fields[0].removeprefix("method ").strip(),
        "resource": fields[1],
        "type": fields[2],
        "leak": fields[3],
        "status": "fixed" if warning.fixed_by else "remaining",
        "fixed_by": warning.fixed_by,
        "reason": warning.reason,
        "fix_patch": warning.fix_patch,
    }
    last = list(warning.runs.values())[-1]
    if not warning.fixed_by and last.line != first.line:
        payload["line_after_repair"] = last.line
    return payload


class _RLFixerView:
    """What RLFixer and RLPatcher did with each warning of the analysis RLFixer ran on."""

    def __init__(self, label: str | None, source_root: Path | None, workspace_root: Path | None,
                 debug: dict[tuple[str, int], str],
                 outcomes: dict[tuple[str, int], tuple[str, bool, str | None]]) -> None:
        self.label = label
        self.source_root = source_root
        self.workspace_root = workspace_root
        self.debug = debug
        self.outcomes = outcomes

    @classmethod
    def load(cls, analyses: Sequence[tuple[str, ReanalyzeResult]], label: str | None,
             stage_results: dict[str, StageResult]) -> "_RLFixerView":
        analysis = dict(analyses).get(label) if label else None
        rlfixer = stage_results.get("rlfixer")
        if analysis is None or rlfixer is None:
            return cls(None, None, None, {}, {})
        metadata = json.loads(analysis.adapter_metadata_path.read_text())
        source_root = Path(metadata["source_root"]).resolve()
        debug = _debug_rows(Path(rlfixer.artifacts["debug"])) if "debug" in rlfixer.artifacts else {}
        outcomes = _patcher_outcomes(stage_results.get("rlpatcher"))
        return cls(label, source_root, analysis.workspace_root.resolve(), debug, outcomes)

    def _key(self, warning: TrackedWarning) -> tuple[str, int] | None:
        diagnostic = warning.runs.get(self.label) if self.label else None
        if diagnostic is None or self.source_root is None or self.workspace_root is None:
            return None
        try:
            relpath = (self.workspace_root / diagnostic.path).resolve().relative_to(self.source_root)
        except ValueError:
            return None
        return relpath.as_posix(), diagnostic.line

    def reason(self, warning: TrackedWarning, *, final_label: str) -> str:
        key = self._key(warning)
        if key is None:
            return "appeared_after_fixes" if warning.first_seen == final_label else "not_analyzed_by_rlfixer"
        if key in self.outcomes:
            outcome, applied, _ = self.outcomes[key]
            if outcome == "materialized":
                return "fix_applied_warning_remains" if applied else "conflict"
            return outcome if outcome in REASONS else "unsupported"
        status = self.debug.get(key)
        if status in ("unmatched", "duplicate", "unfixable"):
            return f"rlfixer_{status}"
        return "no_suggestion"

    def patch_for(self, warning: TrackedWarning) -> str | None:
        key = self._key(warning)
        if key is None or key not in self.outcomes:
            return None
        outcome, applied, patch = self.outcomes[key]
        return patch if outcome == "materialized" and applied else None


def _debug_rows(path: Path) -> dict[tuple[str, int], str]:
    lines = [line.strip() for line in path.read_text(errors="replace").splitlines() if line.strip()]
    if not lines or "^" not in lines[0]:
        return {}
    columns = {name: index for index, name in enumerate(lines[0].split("^"))}
    needed = ("Source File", "Line Number", "Matched Method", "Duplicate", "Unfixable")
    if any(name not in columns for name in needed):
        return {}
    rows: dict[tuple[str, int], str] = {}
    for row in lines[1:]:
        parts = row.split("^")
        if len(parts) < len(columns):
            continue
        try:
            key = (parts[columns["Source File"]].strip().replace("\\", "/"), int(parts[columns["Line Number"]]))
        except ValueError:
            continue
        if parts[columns["Matched Method"]].strip().upper() == "UNMATCHED":
            rows[key] = "unmatched"
        elif parts[columns["Duplicate"]].strip().lower() != "false":
            rows[key] = "duplicate"
        elif parts[columns["Unfixable"]].strip().lower() != "false":
            rows[key] = "unfixable"
        else:
            rows[key] = "fixable"
    return rows


def _patcher_outcomes(result: StageResult | None) -> dict[tuple[str, int], tuple[str, bool, str | None]]:
    if result is None or "patch_manifest" not in result.artifacts:
        return {}
    manifest = json.loads(Path(result.artifacts["patch_manifest"]).read_text())
    patches = {}
    for entry in manifest.get("patches", []):
        name = Path(entry["patch_file"]).name
        match = re.match(r"patch-(\d+)-", name)
        if match:
            patches[int(match.group(1))] = (entry["patch_file"], bool(entry.get("applied_to_workspace", True)))
    outcomes = {}
    for fix in manifest.get("fixes", []):
        patch_file, applied = patches.get(fix["index"], (None, False))
        outcomes[(fix["file"], fix["line"])] = (fix["outcome"], applied, patch_file)
    return outcomes


def _relative(filepath: str, root: Path) -> str:
    try:
        return Path(filepath).resolve().relative_to(root).as_posix()
    except ValueError:
        return filepath
