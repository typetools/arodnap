import json
import logging
import os
import re
import subprocess
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple


# -------------------------
# Parsing helpers
# -------------------------

def get_checkerframework_warnings(content: str) -> List[dict]:
    """
    Parse Checker Framework warnings from the log files.
    Only matches blocks starting with: /abs/path/File.java:<line>: warning:
    """
    warnings = []
    warning_pattern = re.compile(r"^(\/.+?):(\d+):\s+warning:", re.MULTILINE)
    matches = list(warning_pattern.finditer(content))

    for i, match in enumerate(matches):
        start = match.start()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(content)
        warning_block = content[start:end].strip()

        warnings.append({
            "filepath": match.group(1),
            "line_number": int(match.group(2)),
            "message": warning_block
        })

    return warnings


def _relpath_under_src(abs_java_path: str) -> str:
    """
    Convert /.../src/foo/bar/Baz.java -> foo/bar/Baz.java
    If we can't find /src/, fall back to basename.
    """
    p = abs_java_path.replace("\\", "/")
    marker = "/src/"
    if marker in p:
        return p.split(marker, 1)[1]
    return os.path.basename(p)


def parse_fix_suggestions(fixes_txt: str) -> List[dict]:
    """
    Parse RLFixer "fixes/<project>.txt" blocks.

    Expected shape:
      0] /abs/.../src/X.java; Line number 25
      vim +25 /abs/.../src/X.java
      ...
      --------------------------------------------
    """
    pattern = re.compile(
        r"""
        ^\s*(?P<number>\d+)\]\s*
        (?P<filepath>.+?);\s*Line\s+number\s+(?P<line_number>\d+)\s*\n
        (?P<suggestion>.*?)
        (?=^\s*-{10,}\s*$|\Z)
        """,
        re.MULTILINE | re.DOTALL | re.VERBOSE
    )

    suggestions = []
    for m in pattern.finditer(fixes_txt):
        filepath = m.group("filepath").strip()
        line_number = int(m.group("line_number"))
        suggestion_block = m.group("suggestion").strip()

        suggestions.append({
            "filepath": filepath,
            "line_number": line_number,
            "suggestion": suggestion_block,
            "relpath": _relpath_under_src(filepath)
        })

    return suggestions


def parse_rlfixer_debug_fixable(debug_txt: str) -> Set[Tuple[str, int]]:
    """
    Parse RLFixer debug/<project>.txt table and return fixable leak keys:

      key = (relpath_under_src, line_number)

    Debug format:
      Index^Source File^Line Number^Matched Method^...^Duplicate^Unfixable^Comments
      0^xpathParser/bench/pt/DefaultParser.java^25^...^false^false^Try-catch Fix;
      5^xx/SimpleCharStream.java^300^UNMATCHED^...^true^NULL^NULL
    """
    lines = [ln.strip() for ln in debug_txt.splitlines() if ln.strip()]
    if not lines:
        return set()

    # Expect header with ^ separators
    header = lines[0]
    if "^" not in header:
        # Not a table — safest fallback: "no filter"
        logging.warning("RLFixer debug file did not look like a ^-table; will treat all fixes as candidates.")
        return set()

    cols = header.split("^")
    idx_map = {name: i for i, name in enumerate(cols)}

    required = ["Source File", "Line Number", "Matched Method", "Duplicate", "Unfixable"]
    for r in required:
        if r not in idx_map:
            logging.warning(f"RLFixer debug header missing column {r!r}; will treat all fixes as candidates.")
            return set()

    fixable: Set[Tuple[str, int]] = set()

    for row in lines[1:]:
        parts = row.split("^")
        if len(parts) < len(cols):
            continue

        rel = parts[idx_map["Source File"]].strip().replace("\\", "/")
        ln_s = parts[idx_map["Line Number"]].strip()
        matched_method = parts[idx_map["Matched Method"]].strip()
        duplicate = parts[idx_map["Duplicate"]].strip().lower()
        unfixable = parts[idx_map["Unfixable"]].strip().lower()

        try:
            ln = int(ln_s)
        except Exception:
            continue

        # Filter logic
        if matched_method.upper() == "UNMATCHED":
            continue
        if duplicate == "true":
            continue
        if unfixable == "true":
            continue
        # Some rows use NULL in Unfixable/Duplicate — treat as non-fixable
        if duplicate == "null" or unfixable == "null":
            continue

        fixable.add((rel, ln))

    return fixable


# -------------------------
# Matching + JSON prompt creation
# -------------------------

def match_fixes_to_warnings(fix_suggestions: List[dict], cf_warnings: List[dict]) -> List[Tuple[dict, dict]]:
    """
    Match by absolute filepath + line number.
    """
    warn_map: Dict[Tuple[str, int], dict] = {
        (w["filepath"], w["line_number"]): w for w in cf_warnings
    }

    matched = []
    for s in fix_suggestions:
        key = (s["filepath"], s["line_number"])
        if key in warn_map:
            matched.append((s, warn_map[key]))
    return matched


def build_prompt_json(cf_warning_block: str, rlfixer_hint_block: str) -> str:
    """
    Build JSON exactly as PromptParser expects:
      {"CF Leaks":[...], "RLFixer hint":[...]}
    """
    obj = {
        "CF Leaks": [cf_warning_block],
        "RLFixer hint": [rlfixer_hint_block],
    }
    return json.dumps(obj, indent=2)


# -------------------------
# Runner
# -------------------------

def run_rlpatcher_for_project(
    project_name: str,
    rlc_results_folder: str,
    rlfixer_results_folder: str,
    rlpatcher_jar: str,
    out_dir: str,
    success_text: str = "Patch applied successfully",
    keep_temp_json: bool = False,
) -> None:
    """
    End-to-end:
      - read CF warning file from rlc_results_folder/<project>.txt
      - read RLFixer fixes/debug from rlfixer_results_folder/{fixes,debug}/<project>.txt
      - filter to fixable leaks using debug table (when available)
      - match fixes <-> CF warnings by (abs filepath, line)
      - for each matched leak:
          create temp JSON -> java -jar RLPatcher.jar --prompt temp.json
          move rlfixer.patch -> out_dir/patch-<project>-<relpath>-L<line>.patch
          delete temp JSON
    """
    jar_path = Path(rlpatcher_jar)
    if not jar_path.is_file():
        raise FileNotFoundError(f"RLPatcher jar not found: {jar_path}")

    rlc_file = Path(rlc_results_folder) / f"{project_name}.txt"
    fixes_file = Path(rlfixer_results_folder) / "fixes" / f"{project_name}.txt"
    debug_file = Path(rlfixer_results_folder) / "debug" / f"{project_name}.txt"

    if not rlc_file.is_file():
        raise FileNotFoundError(f"CF warnings file not found: {rlc_file}")
    if not fixes_file.is_file():
        raise FileNotFoundError(f"RLFixer fixes file not found: {fixes_file}")

    cf_text = rlc_file.read_text(errors="replace")
    fixes_text = fixes_file.read_text(errors="replace")
    debug_text = debug_file.read_text(errors="replace") if debug_file.is_file() else ""

    cf_warnings = get_checkerframework_warnings(cf_text)
    all_fixes = parse_fix_suggestions(fixes_text)

    # Optional: filter fixes by debug "fixable" set.
    fixable_keys = parse_rlfixer_debug_fixable(debug_text) if debug_text else set()
    if fixable_keys:
        fixes = [s for s in all_fixes if (s["relpath"], s["line_number"]) in fixable_keys]
        logging.info(f"[RLPatcher] RLFixer fixes: {len(all_fixes)} total, {len(fixes)} fixable (per debug).")
    else:
        fixes = all_fixes
        logging.info(f"[RLPatcher] RLFixer fixes: {len(all_fixes)} total (no debug filter applied).")

    # Match by abs path + line
    matched = match_fixes_to_warnings(fixes, cf_warnings)
    logging.info(f"[RLPatcher] CF warnings: {len(cf_warnings)} total, matched: {len(matched)}.")

    if not matched:
        logging.warning("[RLPatcher] No matched (fix, warning) pairs. Skipping.")
        return

    out_path = Path(out_dir)
    out_path.mkdir(parents=True, exist_ok=True)

    tmp_dir = out_path / "_tmp_prompts"
    tmp_dir.mkdir(parents=True, exist_ok=True)

    success = 0
    failure = 0

    for idx, (fix, warn) in enumerate(matched, start=1):
        # Build JSON prompt
        prompt_json = build_prompt_json(
            cf_warning_block=warn["message"],
            rlfixer_hint_block=fix["suggestion"]
        )

        # Temp JSON path
        safe_rel = fix["relpath"].replace("/", "_").replace("\\", "_")
        temp_json_path = tmp_dir / f"prompt-{project_name}-{safe_rel}-L{fix['line_number']}-{idx:04d}.json"
        temp_json_path.write_text(prompt_json)

        # Run jar
        cmd = ["java", "-jar", str(jar_path), "--prompt", str(temp_json_path)]
        logging.info(f"[RLPatcher] ▶ {' '.join(cmd)}")
        result = subprocess.run(cmd, capture_output=True, text=True)

        if result.stdout:
            logging.info(f"[RLPatcher] stdout:\n{result.stdout.strip()}")
        if result.stderr:
            logging.info(f"[RLPatcher] stderr:\n{result.stderr.strip()}")

        ok = bool(result.stdout and success_text in result.stdout)

        # Move produced patch if present
        produced = Path("rlfixer.patch")
        if produced.exists():
            target = out_path / f"patch-{project_name}-{safe_rel}-L{fix['line_number']}.patch"
            target.write_bytes(produced.read_bytes())
            produced.unlink()
            logging.info(f"[RLPatcher] ↪ moved patch → {target}")

        # Remove temp json
        if keep_temp_json:
            logging.info(f"[RLPatcher] keeping temp JSON: {temp_json_path}")
        else:
            try:
                temp_json_path.unlink(missing_ok=True)
            except Exception:
                pass

        if ok:
            success += 1
            logging.info(f"[RLPatcher] [{idx:04d}] ✔ success")
        else:
            failure += 1
            logging.info(f"[RLPatcher] [{idx:04d}] ✖ failed")

    logging.info(f"[RLPatcher] Done — matched: {len(matched)}, success: {success}, failed: {failure}")
