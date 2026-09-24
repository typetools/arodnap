"""Field transformations: private resource fields become final, or local variables when every
method assigns them before use (the paper's "preventing reassignment" and "reducing scope").

This stage runs before the initial analysis, on the build captured for it, so it needs no extra
analysis run. The two Error Prone checks in restructure_plugins/FieldTransformations edit the
workspace in place; each of their edits is already compile-checked for its own file. After each
check the whole program is compiled, and the edits of any file javac reports errors in are undone
until it compiles. The changes are recorded per field and as one normalized patch.
"""

from __future__ import annotations

from dataclasses import dataclass
import difflib
import json
import os
from pathlib import Path
import re
import tempfile

from arodnap.contracts import RunConfig, StageResult
from arodnap.resources import jar
from arodnap.runtime import JdkResolutionError, javac_language_options, resolve_jdk

from .base import (
    CompileInputs,
    StageExecutionError,
    append_command_log,
    run_stage_command,
    stage_timeout_seconds,
    write_stage_result,
)

_STAGE_NAME = "field_transformations"
PLUGIN_JAR = jar("arodnap-field-transformations.jar")
DATAFLOW_JAR = jar("dataflow-errorprone-3.41.0-eisop1.jar")
# Error Prone uses javac internals, so each release supports a range of JDKs. Releases from
# 2.43 need JDK 21 to run and keep up with new JDKs; 2.42.0 is the last one that runs on 17.
LATEST_ERROR_PRONE = (21, jar("error_prone_core-2.50.0-with-dependencies.jar"))
JDK17_ERROR_PRONE = jar("error_prone_core-2.42.0-with-dependencies.jar")
CHECKS = (("ResourceFieldCanBeFinal", "final"), ("ResourceFieldCanBeLocal", "local"))
MODES = ("resources", "all", "off")
# Types the Checker Framework's annotated JDK marks @MustCall without being AutoCloseable, with
# their must-call methods. tests/test_field_transformations.py re-derives this list from the
# bundled checker.jar so a Checker Framework upgrade that adds one is noticed.
EXTRA_RESOURCE_TYPES = ("java.net.HttpURLConnection:disconnect",)
_MAX_REPAIR_ROUNDS = 20
_JAVAC_EXPORTS = tuple(
    [f"-J--add-exports=jdk.compiler/com.sun.tools.javac.{package}=ALL-UNNAMED"
     for package in ("api", "file", "main", "model", "parser", "processing", "tree", "util")]
    + [f"-J--add-opens=jdk.compiler/com.sun.tools.javac.{package}=ALL-UNNAMED" for package in ("code", "comp")]
)
_MATCH = re.compile(
    # Error Prone reports SUGGESTION-level findings as javac notes.
    r"^(?P<path>.+?\.java):(?P<line>\d+): (?:warning|Note|note): \[(?P<check>ResourceFieldCan\w+)\] Resource field `(?P<field>[^`]+)`",
    re.MULTILINE,
)
_ERROR_FILE = re.compile(r"^(?P<path>.+?\.java):\d+: error:", re.MULTILINE)
_IDENTIFIER_LITERAL = re.compile(r'"([A-Za-z_$][A-Za-z0-9_$]*)"')


@dataclass(frozen=True)
class FieldChange:
    file: str
    line: int
    field: str
    change: str  # "final" or "local"
    kept: bool

    def to_dict(self) -> dict[str, object]:
        return {"file": self.file, "line": self.line, "field": self.field, "change": self.change, "kept": self.kept}


def run_field_transformations_stage(
    config: RunConfig,
    *,
    workspace_root: Path,
    stage_output_dir: Path,
    compile_inputs: CompileInputs,
) -> StageResult:
    root = stage_output_dir.resolve()
    root.mkdir(parents=True, exist_ok=True)
    log_path = root / "stage.log"
    log_path.write_text("")
    workspace_root = workspace_root.resolve()
    mode = config.field_transformations
    if mode == "off":
        return _finish(root, changed_files=[], notes=["Field transformations are off (--field-transformations=off)."],
                       artifacts={"log": str(log_path)})

    try:
        jdk = resolve_jdk()
    except JdkResolutionError as exc:
        raise StageExecutionError(str(exc)) from exc
    javac = str(jdk.home / "bin" / "javac")
    error_prone_jar, error_prone_flags = error_prone_for(jdk.major_version)
    for jar in (PLUGIN_JAR, DATAFLOW_JAR, error_prone_jar):
        if not jar.is_file():
            raise StageExecutionError(f"Missing {jar.name} in {jar.parent}")

    sources = [Path(line.strip().strip('"')) for line in compile_inputs.sources_file.read_text().splitlines() if line.strip()]
    originals = {path: path.read_bytes() for path in sources if path.is_file()}
    reflected = root / "reflected-names.txt"
    reflected.write_text("\n".join(sorted(_string_literal_names(originals.values()))) + "\n")
    classpath = os.pathsep.join(
        line.strip() for line in compile_inputs.classpath_file.read_text().splitlines() if line.strip()
    )
    language = javac_language_options(compile_inputs.release, compile_inputs.encoding)

    changes: list[FieldChange] = []
    dropped_files: set[Path] = set()
    for check, change in CHECKS:
        before = {path: path.read_bytes() for path in originals}
        options = " ".join([
            "-Xplugin:ErrorProne", "-XepDisableAllChecks", f"-Xep:{check}", f"-XepPatchChecks:{check}",
            "-XepPatchLocation:IN_PLACE",
            f"-XepOpt:ArodnapFields:ExtraResourceTypes={','.join(EXTRA_RESOURCE_TYPES)}",
            f"-XepOpt:ArodnapFields:ReflectedNamesFile={reflected}",
            f"-XepOpt:ArodnapFields:AllFields={'true' if mode == 'all' else 'false'}",
        ])
        with tempfile.TemporaryDirectory(prefix="arodnap-fields-") as classes:
            command = [
                javac, *_JAVAC_EXPORTS, "-XDcompilePolicy=simple", "--should-stop=ifError=FLOW", *error_prone_flags,
                "-processorpath", os.pathsep.join(str(jar) for jar in (error_prone_jar, DATAFLOW_JAR, PLUGIN_JAR)),
                options, "-proc:none", "-d", classes, "-classpath", classpath, *language,
                f"@{compile_inputs.sources_file.resolve()}",
            ]
            completed = run_stage_command(command=command, cwd=workspace_root, timeout_seconds=stage_timeout_seconds(config))
        append_command_log(log_path, title=check, command=command, completed=completed, tool_name="error-prone",
                           timeout_seconds=stage_timeout_seconds(config))
        output = completed.stdout + completed.stderr
        if completed.returncode != 0:
            raise StageExecutionError(
                f"Error Prone failed while running {check} (see {log_path}). "
                "Use --field-transformations=off to skip field transformations."
            )
        matched = [
            (Path(match.group("path")).resolve(), int(match.group("line")), match.group("field"))
            for match in _MATCH.finditer(output)
        ]
        edited = {path for path in before if path.read_bytes() != before[path]}
        undone = _compile_until_clean(
            javac, classpath, language, compile_inputs, before, edited, log_path, config, title=check
        )
        dropped_files |= undone
        for path, line, field in matched:
            if path in before:
                changes.append(FieldChange(_relative(path, workspace_root), line, field, change, path not in undone))

    changed = sorted(path for path in originals if path.read_bytes() != originals[path])
    patch_text = "".join(_unified_diff(_relative(path, workspace_root), originals[path], path.read_bytes()) for path in changed)
    artifacts = {"log": str(log_path), "field_changes": str(root / "field_changes.json")}
    if patch_text:
        (root / "field_transformations.patch").write_text(patch_text)
        artifacts["patch"] = str(root / "field_transformations.patch")
    (root / "field_changes.json").write_text(
        json.dumps({"mode": mode, "changes": [change.to_dict() for change in changes]}, indent=2, sort_keys=True) + "\n"
    )
    kept = [change for change in changes if change.kept]
    notes = [
        f"Made {sum(1 for c in kept if c.change == 'final')} resource field(s) final and turned "
        f"{sum(1 for c in kept if c.change == 'local')} into local variables ({mode})."
    ]
    if dropped_files:
        notes.append(f"Undid the changes to {len(dropped_files)} file(s) that did not compile with them.")
    return _finish(root, changed_files=[_relative(path, workspace_root) for path in changed], notes=notes,
                   artifacts=artifacts)


def error_prone_for(jdk_major: int) -> tuple[Path, list[str]]:
    """The Error Prone jar that runs on this JDK, and the javac flags it needs."""
    minimum, latest = LATEST_ERROR_PRONE
    if jdk_major >= minimum:
        # Required by Error Prone 2.46+ on JDK 21 (JDK-8225377); harmless on newer JDKs.
        return latest, ["-XDaddTypeAnnotationsToSymbol=true"]
    return JDK17_ERROR_PRONE, []


def _compile_until_clean(javac, classpath, language, compile_inputs, before, edited, log_path, config, *, title) -> set[Path]:
    """Compile the program; undo the edits in files javac reports errors in, until it compiles."""
    undone: set[Path] = set()
    for round_number in range(1, _MAX_REPAIR_ROUNDS + 1):
        with tempfile.TemporaryDirectory(prefix="arodnap-fields-check-") as classes:
            command = [javac, "-proc:none", "-nowarn", "-d", classes, "-classpath", classpath, *language,
                       f"@{compile_inputs.sources_file.resolve()}"]
            completed = run_stage_command(command=command, cwd=compile_inputs.sources_file.parent,
                                          timeout_seconds=stage_timeout_seconds(config))
        append_command_log(log_path, title=f"{title} compile check {round_number}", command=command,
                           completed=completed, tool_name="javac", timeout_seconds=stage_timeout_seconds(config))
        if completed.returncode == 0:
            return undone
        failing = {Path(match.group("path")).resolve() for match in _ERROR_FILE.finditer(completed.stdout + completed.stderr)}
        to_undo = (failing & edited) - undone
        if not to_undo:
            # The errors are not in edited files; fail rather than report broken code as fine.
            raise StageExecutionError(f"The program no longer compiles after {title} (see {log_path}).")
        for path in to_undo:
            path.write_bytes(before[path])
        undone |= to_undo
    raise StageExecutionError(f"Could not get the program to compile after {title} (see {log_path}).")


def _string_literal_names(contents) -> set[str]:
    """Identifiers that appear as string literals anywhere: fields reflection could look up."""
    names: set[str] = set()
    for data in contents:
        names |= set(_IDENTIFIER_LITERAL.findall(data.decode("utf-8", errors="replace")))
    return names


def _unified_diff(relpath: str, old: bytes, new: bytes) -> str:
    old_text = old.decode("utf-8", errors="surrogateescape")
    new_text = new.decode("utf-8", errors="surrogateescape")
    lines = []
    for line in difflib.unified_diff(old_text.splitlines(keepends=True), new_text.splitlines(keepends=True),
                                     fromfile=relpath, tofile=relpath):
        lines.append(line if line.endswith("\n") else line + "\n\\ No newline at end of file\n")
    return "".join(lines)


def _relative(path: Path, root: Path) -> str:
    try:
        return path.resolve().relative_to(root).as_posix()
    except ValueError:
        return str(path)


def _finish(root: Path, *, changed_files: list[str], notes: list[str], artifacts: dict[str, str]) -> StageResult:
    result = StageResult(
        stage=_STAGE_NAME,
        changed=bool(changed_files),
        changed_files=changed_files,
        rerun_required=False,  # it runs before the initial analysis
        artifacts=artifacts,
        notes=notes,
        success=True,
    )
    write_stage_result(root, result)
    return result


__all__ = ["MODES", "FieldChange", "error_prone_for", "run_field_transformations_stage"]
