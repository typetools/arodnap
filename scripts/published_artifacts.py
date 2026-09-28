"""Checks that Arodnap works from its published artifacts alone.

    python3 scripts/published_artifacts.py stage                   # publish to build/staging, no credentials
    python3 scripts/published_artifacts.py check                   # use them from build/staging
    python3 scripts/published_artifacts.py check --from central --version 0.1.0   # after a release

`stage` publishes everything a release publishes (every Maven module, the tools, the command-line
zip and the Gradle plugin) into a folder that stands in for Maven Central. `check` then repairs a
test project three ways, each starting from empty caches (a new Maven local repository and Gradle
user home) with only that folder, Maven Central and the Gradle Plugin Portal to download from, so
nothing comes from this checkout or ~/.m2:

- the Maven plugin repairs test-projects/maven-dependency-leak
- the Gradle plugin repairs test-projects/gradle-dependency-leak
- the command line, unpacked from the published zip, repairs test-projects/javac-script

A check passes when the repair succeeds and its patch changes exactly the leaking source.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
TEST_PROJECTS = REPO / "test-projects"
CENTRAL = "https://repo1.maven.org/maven2"


def coordinates() -> tuple[str, str]:
    """The group and version this checkout builds, from the parent POM."""
    match = re.search(r"<groupId>([^<]+)</groupId>\s*<artifactId>arodnap-parent</artifactId>\s*<version>([^<]+)</version>",
                      (REPO / "pom.xml").read_text())
    if not match:
        sys.exit("Cannot read Arodnap's group and version from pom.xml.")
    return match.group(1), match.group(2)


def run(command: list[str], cwd: Path, log: Path) -> None:
    print(f"   $ {' '.join(command)}", flush=True)
    with log.open("w") as out:
        code = subprocess.run(command, cwd=cwd, stdout=out, stderr=subprocess.STDOUT).returncode
    if code != 0:
        print(log.read_text()[-4000:], file=sys.stderr)
        raise SystemExit(f"failed ({code}): {' '.join(command)}; see {log}")


def stage(staging: Path) -> None:
    shutil.rmtree(staging, ignore_errors=True)
    staging.mkdir(parents=True)
    logs = staging.parent
    print(f"== staging every artifact in {staging}", flush=True)
    run(["mvn", "-B", "-ntp", "deploy", "-DskipTests", f"-DaltDeploymentRepository=staging::{staging.as_uri()}"], REPO,
        logs / "stage-maven.log")
    run(["gradle", "-p", "arodnap-gradle-plugin", "--console=plain", "publishAllPublicationsToStagingRepository",
         f"-PstagingRepository={staging}"], REPO, logs / "stage-gradle.log")


def patched_files(out: Path) -> list[str]:
    report = json.loads((out / "report.json").read_text())
    if not report.get("success"):
        raise SystemExit(f"the repair failed: {report.get('error')}; see {out / 'report.json'}")
    manifest = json.loads((out / "patches" / "manifest.json").read_text())
    return sorted(file for patch in manifest["patches"] for file in patch["changed_files"])


def expect(name: str, out: Path, expected: str) -> None:
    files = patched_files(out)
    if files != [expected]:
        raise SystemExit(f"{name}: expected a patch of {expected}, got {files}")
    print(f"   ok: the patch changes {expected}", flush=True)


def check_maven_plugin(group: str, version: str, staging: Path | None, work: Path) -> None:
    print("== the Maven plugin", flush=True)
    project = work / "maven"
    shutil.copytree(TEST_PROJECTS / "maven-dependency-leak", project)
    repositories = ""
    if staging:
        repository = (f"<id>staging</id><url>{staging.as_uri()}</url>"
                      "<releases><enabled>true</enabled></releases><snapshots><enabled>true</enabled></snapshots>")
        repositories = (f"<repositories><repository>{repository}</repository></repositories>"
                        f"<pluginRepositories><pluginRepository>{repository}</pluginRepository></pluginRepositories>")
    settings = work / "settings.xml"
    settings.write_text(f"<settings><profiles><profile><id>published</id>{repositories}</profile></profiles>"
                        "<activeProfiles><activeProfile>published</activeProfile></activeProfiles></settings>\n")
    out = work / "maven-out"
    run(["mvn", "-B", "-ntp", "-s", str(settings), f"-Dmaven.repo.local={work / 'maven-repository'}", "compile",
         f"{group}:arodnap-maven-plugin:{version}:repair", f"-Darodnap.outputDirectory={out}"], project, work / "maven.log")
    expect("Maven plugin", out, "src/main/java/demo/ReadAll.java")


def check_gradle_plugin(group: str, version: str, staging: Path | None, work: Path) -> None:
    print("== the Gradle plugin", flush=True)
    project = work / "gradle"
    shutil.copytree(TEST_PROJECTS / "gradle-dependency-leak", project)
    staging_repository = f"        maven {{ url = uri('{staging.as_uri()}') }}\n" if staging else ""
    settings = project / "settings.gradle"
    settings.write_text(f"pluginManagement {{\n    repositories {{\n{staging_repository}        gradlePluginPortal()\n    }}\n}}\n\n"
                        + settings.read_text())
    build = project / "build.gradle"
    text = build.read_text()
    text = text.replace("    id 'java'\n", f"    id 'java'\n    id '{group}.arodnap' version '{version}'\n", 1)
    text = text.replace("repositories {\n    mavenCentral()\n}", "repositories {\n" + staging_repository.replace("        ", "    ", 1)
                        + "    mavenCentral()\n}", 1)
    build.write_text(text)
    run(["gradle", "--no-daemon", "--console=plain", "--gradle-user-home", str(work / "gradle-home"), "arodnapRepair"], project,
        work / "gradle.log")
    expect("Gradle plugin", project / "build" / "arodnap", "src/main/java/com/arodnap/fixture/DependencyLeakExample.java")


def check_command_line(group: str, version: str, staging: Path | None, work: Path) -> None:
    print("== the command line from the published zip", flush=True)
    path = f"{group.replace('.', '/')}/arodnap-distribution/{version}"
    if staging:
        zips = sorted((staging / path).glob("arodnap-distribution-*-bin.zip"))
        if not zips:
            raise SystemExit(f"no arodnap-distribution zip in {staging / path}")
        archive = zips[-1]
    else:
        archive = work / f"arodnap-distribution-{version}-bin.zip"
        urllib.request.urlretrieve(f"{CENTRAL}/{path}/{archive.name}", archive)
    run(["unzip", "-q", str(archive), "-d", str(work / "cli")], work, work / "unzip.log")
    launcher = next((work / "cli").glob("arodnap-*/bin/arodnap"))
    project = work / "javac-script"
    shutil.copytree(TEST_PROJECTS / "javac-script", project)
    out = work / "cli-out"
    run([str(launcher), "repair", str(project), "--out-dir", str(out), "--", "./build.sh"], work, work / "cli.log")
    expect("command line", out, "src/demo/FirstByte.java")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    stage_parser = commands.add_parser("stage", help="publish every artifact into a folder standing in for Maven Central")
    stage_parser.add_argument("--staging", type=Path, default=REPO / "build" / "staging")
    check_parser = commands.add_parser("check", help="repair test projects with the published artifacts only")
    check_parser.add_argument("--from", dest="source", choices=("staging", "central"), default="staging")
    check_parser.add_argument("--staging", type=Path, default=REPO / "build" / "staging")
    check_parser.add_argument("--version", help="the released version (default: this checkout's)")
    check_parser.add_argument("--work-dir", type=Path, default=REPO / "build" / "published-check")
    args = parser.parse_args()

    if args.command == "stage":
        stage(args.staging.resolve())
        return 0
    group, version = coordinates()
    version = args.version or version
    staging = args.staging.resolve() if args.source == "staging" else None
    work = args.work_dir.resolve()
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)
    check_maven_plugin(group, version, staging, work)
    check_gradle_plugin(group, version, staging, work)
    check_command_line(group, version, staging, work)
    print(f"Arodnap {version} works from its published artifacts.", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
