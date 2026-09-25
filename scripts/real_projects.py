"""Runs `arodnap repair` on real open-source projects and checks the results.

    python scripts/real_projects.py list                  # project names, one per line
    python scripts/real_projects.py run commons-io jsoup  # clone, repair, compare
    python scripts/real_projects.py run --all
    python scripts/real_projects.py run jsoup --record    # store the results as expected

Each project in `real_projects.json` is cloned fresh from its own repository at a pinned
release, then repaired with this checkout's Arodnap. A run passes when `repair` succeeds
(its patch was replayed onto a clean copy and compiled) and, once a project has expected
results, when the leak counts match them exactly. Time and peak memory are reported, not
compared.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))

from arodnap.unified_patch import parse_unified_diff  # noqa: E402
PROJECTS_FILE = Path(__file__).with_name("real_projects.json")
# Counts from report.json that must match the recorded expectation.
COMPARED = ("initial", "found_during_repair", "fixed", "remaining", "patched_files")


def load_projects() -> list[dict]:
    return json.loads(PROJECTS_FILE.read_text())["projects"]


def clone(project: dict, destination: Path) -> None:
    destination.mkdir(parents=True)
    for command in (
        ["git", "init", "-q"],
        ["git", "fetch", "-q", "--depth", "1", project["repo"], project["ref"]],
        ["git", "-c", "advice.detachedHead=false", "checkout", "-q", "FETCH_HEAD"],
    ):
        subprocess.run(command, cwd=destination, check=True)


def repair(project: dict, clone_dir: Path, out_dir: Path, log_path: Path) -> tuple[int, float, int]:
    """Runs `arodnap repair`; returns (exit code, seconds, peak memory of its largest process in MB)."""
    command = [sys.executable, "-m", "arodnap.main", "repair", str(clone_dir), "--out-dir", str(out_dir)]
    command += project.get("args", [])
    if project.get("command"):
        command += ["--", *project["command"]]
    started = time.monotonic()
    with log_path.open("w") as log:
        process = subprocess.Popen(command, cwd=REPO, stdout=log, stderr=subprocess.STDOUT)
        _, status, usage = os.wait4(process.pid, 0)
    seconds = time.monotonic() - started
    # ru_maxrss is in kilobytes on Linux and bytes on macOS.
    peak_mb = usage.ru_maxrss // (1024 * 1024 if sys.platform == "darwin" else 1024)
    return os.waitstatus_to_exitcode(status), seconds, peak_mb


def summarize(out_dir: Path) -> dict:
    report_path = out_dir / "report.json"
    if not report_path.is_file():
        return {}
    report = json.loads(report_path.read_text())
    summary = dict((report.get("leaks") or {}).get("summary") or {})
    patch = out_dir / "patches" / "arodnap.patch"
    summary["patched_files"] = len(parse_unified_diff(patch.read_text())) if patch.is_file() and patch.stat().st_size else 0
    summary["warnings_by_run"] = {run["label"]: run.get("warning_count") for run in report.get("analysis_runs", [])}
    return summary


def run(names: list[str], work_dir: Path, record: bool) -> int:
    projects = {project["name"]: project for project in load_projects()}
    unknown = [name for name in names if name not in projects]
    if unknown:
        print(f"unknown project(s): {', '.join(unknown)}", file=sys.stderr)
        return 2
    results = []
    for name in names:
        project = projects[name]
        project_dir = work_dir / name
        shutil.rmtree(project_dir, ignore_errors=True)
        print(f"== {name} ({project['ref']})", flush=True)
        clone(project, project_dir / "src")
        code, seconds, peak_mb = repair(project, project_dir / "src", project_dir / "out", project_dir / "repair.log")
        summary = summarize(project_dir / "out")
        expected = project.get("expected")
        problems = []
        if code != 0:
            problems.append(f"repair exited with {code}; see {project_dir / 'repair.log'}")
        elif expected and not record:
            for key in COMPARED:
                if summary.get(key) != expected.get(key):
                    problems.append(f"{key}: expected {expected.get(key)}, got {summary.get(key)}")
        if record and code == 0:
            project["expected"] = {key: summary.get(key) for key in COMPARED}
        project["observed"] = {"minutes": round(seconds / 60, 1), "peak_memory_mb": peak_mb}
        results.append({"name": name, "ok": not problems, "problems": problems, "seconds": round(seconds),
                        "peak_memory_mb": peak_mb, "summary": summary, "expected": expected})
        status = "ok" if not problems else "FAILED"
        print(f"   {status} in {seconds / 60:.1f} min, peak {peak_mb} MB, {json.dumps(summary)}", flush=True)
        for problem in problems:
            print(f"   - {problem}", flush=True)

    if record:
        PROJECTS_FILE.write_text(json.dumps({"projects": list(projects.values())}, indent=2) + "\n")
    (work_dir / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    _write_step_summary(results)
    return 0 if all(result["ok"] for result in results) else 1


def _write_step_summary(results: list[dict]) -> None:
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not path:
        return
    lines = ["| Project | Result | Leaks found | Fixed | Remaining | Time | Peak memory |", "|---|---|---|---|---|---|---|"]
    for result in results:
        summary = result["summary"]
        found = (summary.get("initial") or 0) + (summary.get("found_during_repair") or 0)
        lines.append(
            f"| {result['name']} | {'ok' if result['ok'] else 'failed'} | {found} | {summary.get('fixed', '-')} | "
            f"{summary.get('remaining', '-')} | {result['seconds'] / 60:.1f} min | {result['peak_memory_mb']} MB |"
        )
    for result in results:
        for problem in result["problems"]:
            lines.append(f"\n- {result['name']}: {problem}")
    with open(path, "a") as handle:
        handle.write("\n".join(lines) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("list", help="print the project names")
    run_parser = commands.add_parser("run", help="clone, repair and check projects")
    run_parser.add_argument("names", nargs="*")
    run_parser.add_argument("--all", action="store_true", help="run every project")
    run_parser.add_argument("--work-dir", type=Path, default=REPO / "build" / "real-projects")
    run_parser.add_argument("--record", action="store_true", help="store the results as the expected ones")
    args = parser.parse_args()
    if args.command == "list":
        print("\n".join(project["name"] for project in load_projects()))
        return 0
    names = [project["name"] for project in load_projects()] if args.all else args.names
    if not names:
        parser.error("name at least one project, or pass --all")
    args.work_dir.mkdir(parents=True, exist_ok=True)
    return run(names, args.work_dir.resolve(), args.record)


if __name__ == "__main__":
    sys.exit(main())
