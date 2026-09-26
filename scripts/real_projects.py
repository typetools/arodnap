"""Runs `arodnap repair` on real open-source projects and checks the results.

    python scripts/real_projects.py list                  # project names, one per line
    python scripts/real_projects.py list --tier quick     # only the quick ones (run on every PR)
    python scripts/real_projects.py run commons-io jsoup  # clone, repair, compare
    python scripts/real_projects.py run --all
    python scripts/real_projects.py run jsoup --record    # store the results as expected
    python scripts/real_projects.py run jsoup --via plugin --tests

Each project in `real_projects.json` is cloned fresh from its own repository at a pinned
release, then repaired by the Arodnap this checkout builds: the command-line distribution
(`mvn package` first), or with `--via plugin` the Maven or Gradle plugin from the local Maven
repository (`mvn install` and `gradle -p arodnap-gradle-plugin publishToMavenLocal` first). A
run passes when `repair` succeeds (its patch was replayed onto a clean copy and compiled) and,
once a project has expected results, when the leak counts match them exactly; both front ends
must give the same counts. With `--tests`, when the repair changed something, the project's own
tests run before the patch, then the patch is applied and they run again: the patch must not
break them. Time and peak memory are reported, not compared. Projects with `"tier": "quick"`
take minutes and run in CI on every pull request; the rest run weekly.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
PROJECTS_FILE = Path(__file__).with_name("real_projects.json")
# Counts from report.json that must match the recorded expectation.
COMPARED = ("initial", "found_during_repair", "fixed", "remaining", "patched_files")
# A project's own tests get this long, before and after the patch.
TEST_TIMEOUT_SECONDS = 90 * 60


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


def timed(command: list[str], cwd: Path, log_path: Path, timeout: float | None = None) -> tuple[int | str, float, int]:
    """Runs a command; returns (exit code or "timeout", seconds, peak memory of its largest process in MB)."""
    started = time.monotonic()
    with log_path.open("w") as log:
        # Its own process group, so a timeout also ends what it started (forked test JVMs, daemons).
        process = subprocess.Popen(command, cwd=cwd, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        while True:
            pid, status, usage = os.wait4(process.pid, os.WNOHANG)
            if pid:
                break
            if timeout is not None and time.monotonic() - started > timeout:
                os.killpg(process.pid, signal.SIGKILL)
                os.wait4(process.pid, 0)
                return "timeout", time.monotonic() - started, 0
            time.sleep(1)
    # ru_maxrss is in kilobytes on Linux and bytes on macOS.
    peak_mb = usage.ru_maxrss // (1024 * 1024 if sys.platform == "darwin" else 1024)
    return os.waitstatus_to_exitcode(status), time.monotonic() - started, peak_mb


class CommandLine:
    """The arodnap command: the distribution this checkout built, or another one."""

    name = "cli"

    def __init__(self, arodnap: list[str]):
        self.arodnap = arodnap

    def supports(self, project: dict) -> bool:
        return True

    def repair(self, project: dict, clone_dir: Path, out_dir: Path, log_path: Path) -> tuple[int | str, float, int]:
        command = [*self.arodnap, "repair", str(clone_dir), "--out-dir", str(out_dir), *project.get("args", [])]
        if project.get("command"):
            command += ["--", *project["command"]]
        return timed(command, REPO, log_path)

    def apply(self, project: dict, clone_dir: Path, out_dir: Path, log_path: Path) -> int | str:
        command = [*self.arodnap, "apply", "--patch-dir", str(out_dir / "patches"), "--out-dir", str(out_dir), str(clone_dir)]
        return timed(command, REPO, log_path)[0]


class Plugins:
    """The Maven plugin for Maven projects and the Gradle plugin for Gradle projects, from the local
    Maven repository. The Gradle plugin is applied by an init script, so the build is unchanged."""

    name = "plugin"

    def __init__(self, version: str):
        self.version = version

    def supports(self, project: dict) -> bool:
        return project.get("build") in ("maven", "gradle")

    def repair(self, project: dict, clone_dir: Path, out_dir: Path, log_path: Path) -> tuple[int | str, float, int]:
        return timed(self._command(project, clone_dir, out_dir, "repair"), clone_dir, log_path)

    def apply(self, project: dict, clone_dir: Path, out_dir: Path, log_path: Path) -> int | str:
        return timed(self._command(project, clone_dir, out_dir, "apply"), clone_dir, log_path)[0]

    def _command(self, project: dict, clone_dir: Path, out_dir: Path, goal: str) -> list[str]:
        if project["build"] == "maven":
            maven = str(clone_dir / "mvnw") if (clone_dir / "mvnw").is_file() else "mvn"
            plugin = f"org.arodnap:arodnap-maven-plugin:{self.version}:{goal}"
            return [maven, "-B", "-ntp", *(["compile"] if goal == "repair" else []), plugin, f"-Darodnap.outputDirectory={out_dir}"]
        init_script = out_dir.parent / "arodnap.init.gradle"
        init_script.write_text(f"""initscript {{
    repositories {{
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
    }}
    dependencies {{
        classpath("org.arodnap:arodnap-gradle-plugin:{self.version}")
    }}
}}
rootProject {{
    apply plugin: org.arodnap.gradle.ArodnapPlugin
    repositories {{
        mavenLocal()
        mavenCentral()
    }}
    arodnap {{
        outputDirectory = file({json.dumps(str(out_dir))})
    }}
}}
""")
        gradle = str(clone_dir / "gradlew") if (clone_dir / "gradlew").is_file() else "gradle"
        return [gradle, "--console=plain", "--init-script", str(init_script), "arodnap" + goal.capitalize()]


def summarize(out_dir: Path) -> dict:
    report_path = out_dir / "report.json"
    if not report_path.is_file():
        return {}
    report = json.loads(report_path.read_text())
    summary = dict((report.get("leaks") or {}).get("summary") or {})
    manifest = out_dir / "patches" / "manifest.json"
    patches = json.loads(manifest.read_text()).get("patches", []) if manifest.is_file() else []
    summary["patched_files"] = sum(len(patch.get("changed_files", [])) for patch in patches)
    summary["warnings_by_run"] = {run["label"]: run.get("warning_count") for run in report.get("analysis_runs", [])}
    return summary


def run_tests(project: dict, front_end, clone_dir: Path, out_dir: Path, project_dir: Path, record: bool) -> tuple[dict, list[str]]:
    """The project's tests without and with the patch; returns (what happened, problems). A patch
    known to break a project's tests is recorded as expected ("tests_after_patch": "broken"), so
    the weekly run flags a change either way instead of failing every week."""
    command = project.get("test")
    if not command:
        return {"tests": "none configured"}, []
    before, before_seconds, _ = timed(command, clone_dir, project_dir / "tests-before.log", TEST_TIMEOUT_SECONDS)
    if before != 0:
        return {"tests_before": before}, [f"the project's tests fail without the patch ({before}); see {project_dir / 'tests-before.log'}"]
    applied = front_end.apply(project, clone_dir, out_dir, project_dir / "apply.log")
    if applied != 0:
        return {"tests_before": 0}, [f"applying the patch failed ({applied}); see {project_dir / 'apply.log'}"]
    after, after_seconds, _ = timed(command, clone_dir, project_dir / "tests-after.log", TEST_TIMEOUT_SECONDS)
    outcome = {"tests_before": 0, "tests_after": after, "test_minutes": round((before_seconds + after_seconds) / 2 / 60, 1),
               "tests_after_patch": "pass" if after == 0 else "broken"}
    expected = (project.get("expected") or {}).get("tests_after_patch", "pass")
    if record or outcome["tests_after_patch"] == expected:
        return outcome, []
    if after != 0:
        return outcome, [f"the patch breaks the project's tests ({after}); see {project_dir / 'tests-after.log'}"]
    return outcome, ["the project's tests now pass with the patch, but they were recorded as broken by it: record again"]


def run(names: list[str], work_dir: Path, record: bool, front_end, tests: bool) -> int:
    projects = {project["name"]: project for project in load_projects()}
    unknown = [name for name in names if name not in projects]
    if unknown:
        print(f"unknown project(s): {', '.join(unknown)}", file=sys.stderr)
        return 2
    results = []
    for name in names:
        project = projects[name]
        if not front_end.supports(project):
            print(f"== {name}: skipped, no {front_end.name} for {project.get('build')} builds", flush=True)
            continue
        project_dir = work_dir / name
        shutil.rmtree(project_dir, ignore_errors=True)
        print(f"== {name} ({project['ref']}, {front_end.name})", flush=True)
        clone_dir, out_dir = project_dir / "src", project_dir / "out"
        clone(project, clone_dir)
        code, seconds, peak_mb = front_end.repair(project, clone_dir, out_dir, project_dir / "repair.log")
        summary = summarize(out_dir)
        expected = project.get("expected")
        problems = []
        if code != 0:
            problems.append(f"repair exited with {code}; see {project_dir / 'repair.log'}")
        elif expected and not record:
            for key in COMPARED:
                if summary.get(key) != expected.get(key):
                    problems.append(f"{key}: expected {expected.get(key)}, got {summary.get(key)}")
        test_outcome = {}
        if tests and code == 0 and summary.get("patched_files"):
            test_outcome, test_problems = run_tests(project, front_end, clone_dir, out_dir, project_dir, record)
            problems += test_problems
        if record and code == 0:
            project["expected"] = {key: summary.get(key) for key in COMPARED}
            if "tests_after_patch" in test_outcome:
                project["expected"]["tests_after_patch"] = test_outcome["tests_after_patch"]
            project["observed"] = {"minutes": round(seconds / 60, 1), "peak_memory_mb": peak_mb}
        results.append({"name": name, "via": front_end.name, "ok": not problems, "problems": problems, "seconds": round(seconds),
                        "peak_memory_mb": peak_mb, "summary": summary, "expected": expected, **test_outcome})
        status = "ok" if not problems else "FAILED"
        print(f"   {status} in {seconds / 60:.1f} min, peak {peak_mb} MB, {json.dumps(summary)}", flush=True)
        if test_outcome:
            print(f"   tests: {json.dumps(test_outcome)}", flush=True)
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
    lines = ["| Project | Via | Result | Leaks found | Fixed | Remaining | Tests after the patch | Time | Peak memory |",
             "|---|---|---|---|---|---|---|---|---|"]
    for result in results:
        summary = result["summary"]
        found = (summary.get("initial") or 0) + (summary.get("found_during_repair") or 0)
        tests = result.get("tests_after_patch", "-")
        lines.append(
            f"| {result['name']} | {result['via']} | {'ok' if result['ok'] else 'failed'} | {found} | {summary.get('fixed', '-')} | "
            f"{summary.get('remaining', '-')} | {tests} | {result['seconds'] / 60:.1f} min | {result['peak_memory_mb']} MB |"
        )
    for result in results:
        for problem in result["problems"]:
            lines.append(f"\n- {result['name']}: {problem}")
    with open(path, "a") as handle:
        handle.write("\n".join(lines) + "\n")


def built_distribution() -> Path:
    """The bin/arodnap of the distribution `mvn package` built in this checkout."""
    launchers = sorted((REPO / "arodnap-distribution" / "target").glob("arodnap-*-bin/arodnap-*/bin/arodnap"))
    if not launchers:
        sys.exit("No Arodnap distribution in arodnap-distribution/target: run `mvn package` first, or pass --arodnap.")
    return launchers[-1]


def arodnap_version() -> str:
    """The version this checkout builds, from the parent POM."""
    match = re.search(r"<artifactId>arodnap-parent</artifactId>\s*<version>([^<]+)</version>", (REPO / "pom.xml").read_text())
    if not match:
        sys.exit("Cannot read Arodnap's version from pom.xml.")
    return match.group(1)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    list_parser = commands.add_parser("list", help="print the project names")
    list_parser.add_argument("--tier", help="only projects of this tier, e.g. quick")
    list_parser.add_argument("--build", help="only projects of this build system, e.g. maven")
    run_parser = commands.add_parser("run", help="clone, repair and check projects")
    run_parser.add_argument("names", nargs="*")
    run_parser.add_argument("--all", action="store_true", help="run every project")
    run_parser.add_argument("--work-dir", type=Path, default=REPO / "build" / "real-projects")
    run_parser.add_argument("--record", action="store_true", help="store the results as the expected ones")
    run_parser.add_argument("--via", choices=("cli", "plugin"), default="cli",
                            help="repair with the command line (default), or with the Maven or Gradle plugin")
    run_parser.add_argument("--tests", action="store_true", help="also run the project's tests before and after applying the patch")
    run_parser.add_argument("--arodnap", help="the arodnap command to run (default: the distribution built in this checkout)")
    args = parser.parse_args()
    if args.command == "list":
        print("\n".join(project["name"] for project in load_projects()
                        if (not args.tier or project.get("tier") == args.tier) and (not args.build or project.get("build") == args.build)))
        return 0
    names = [project["name"] for project in load_projects()] if args.all else args.names
    if not names:
        parser.error("name at least one project, or pass --all")
    args.work_dir.mkdir(parents=True, exist_ok=True)
    if args.via == "plugin":
        front_end = Plugins(arodnap_version())
    else:
        front_end = CommandLine(shlex.split(args.arodnap) if args.arodnap else [str(built_distribution())])
    return run(names, args.work_dir.resolve(), args.record, front_end, args.tests)


if __name__ == "__main__":
    sys.exit(main())
