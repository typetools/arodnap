from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import os
import sys
from typing import Any, Literal

from arodnap.build_adapters import (
    AdapterExecutionError,
    MissingBuildToolError,
    ProjectModel,
    UnsupportedProjectError,
    select_build_adapter,
)
from arodnap.contracts import RunConfig
from arodnap.orchestrator.results import write_json
from arodnap.orchestrator.workspace import copied_workspace
from arodnap.patch_tool import PatchToolError, discover_patch_tool
from arodnap.analysis.wpi_runner import resolve_dljc_python, wpi_supported_jdk_majors
from arodnap.runtime import (
    RLFIXER_MIN_JDK_MAJOR,
    JdkResolutionError,
    resolve_jdk,
)


DoctorStatus = Literal["ok", "warning", "error"]


@dataclass(frozen=True)
class DoctorCheck:
    name: str
    status: DoctorStatus
    message: str
    details: dict[str, Any] | None = None

    def to_payload(self) -> dict[str, Any]:
        payload = {
            "name": self.name,
            "status": self.status,
            "message": self.message,
        }
        if self.details is not None:
            payload["details"] = _jsonable(self.details)
        return payload


@dataclass(frozen=True)
class DoctorReport:
    repo_root: Path
    out_dir: Path
    build_args: tuple[str, ...]
    compile_target: str | None
    success: bool
    checks: tuple[DoctorCheck, ...]

    def to_payload(self) -> dict[str, Any]:
        return {
            "repo_root": str(self.repo_root),
            "out_dir": str(self.out_dir),
            "build_args": list(self.build_args),
            "compile_target": self.compile_target,
            "success": self.success,
            "checks": [check.to_payload() for check in self.checks],
        }


def run_doctor(config: RunConfig) -> int:
    checks: list[DoctorCheck] = []
    checks.extend(_run_environment_checks(config))

    repo_path_check = _check_repo_path(config.repo_root)
    checks.append(repo_path_check)
    if repo_path_check.status == "ok":
        try:
            with copied_workspace(config.repo_root, keep_workspace=config.keep_workspace) as workspace:
                checks.extend(
                    _run_repo_checks(
                        config,
                        workspace_root=workspace.workspace_root,
                    )
                )
        except OSError as exc:
            checks.append(
                DoctorCheck(
                    name="workspace_copy",
                    status="error",
                    message=f"Failed to create workspace copy for doctor: {exc}",
                    details={"repo_root": config.repo_root},
                )
            )

    success = all(check.status != "error" for check in checks)
    report = DoctorReport(
        repo_root=config.repo_root,
        out_dir=config.out_dir,
        build_args=tuple(config.build_args),
        compile_target=config.compile_target,
        success=success,
        checks=tuple(checks),
    )
    report_path = write_json(config.out_dir / "doctor.json", report.to_payload())
    _print_summary(report, report_path)
    return 0 if success else 1


def _run_environment_checks(config: RunConfig) -> list[DoctorCheck]:
    return [
        _check_python_runtime(),
        _check_java_runtime(config.cf_root),
        _check_wpi_gradle_jdk(config.cf_root),
        _check_wpi_python(),
        _check_patch_binary(),
        _check_checker_framework_path(config.cf_root),
        _check_checker_framework_tools(config.cf_root),
        _check_plugin_jars(config),
    ]


def _check_python_runtime() -> DoctorCheck:
    version = f"{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}"
    return DoctorCheck(
        name="python_runtime",
        status="ok",
        message=f"Python {version} is available.",
        details={"executable": Path(sys.executable), "version": version},
    )


def _check_java_runtime(cf_root: Path) -> DoctorCheck:
    try:
        jdk = resolve_jdk()
    except JdkResolutionError as exc:
        return DoctorCheck(name="java_runtime", status="error", message=str(exc))

    details = {"home": jdk.home, "major_version": jdk.major_version, "source": jdk.source}
    supported = [
        major
        for major in wpi_supported_jdk_majors(cf_root)
        if major >= RLFIXER_MIN_JDK_MAJOR
    ]
    if jdk.major_version not in supported:
        return DoctorCheck(
            name="java_runtime",
            status="error",
            message=(
                f"JDK {jdk.major_version} at {jdk.home} (from {jdk.source}) is not supported "
                f"with {cf_root.name}. "
                f"Set JAVA_HOME to JDK {' or '.join(str(major) for major in supported)}."
            ),
            details=details,
        )
    return DoctorCheck(
        name="java_runtime",
        status="ok",
        message=f"JDK {jdk.major_version} is available at {jdk.home} (from {jdk.source}).",
        details=details,
    )


def _check_wpi_gradle_jdk(cf_root: Path) -> DoctorCheck:
    """wpi.sh (Checker Framework 4.2.3 and master as of 2026-09) runs Gradle builds with
    -Dorg.gradle.java.home=${JAVA21_HOME}, so Gradle projects need a JDK 21 even when
    JAVA_HOME is newer."""
    try:
        wpi_script = (cf_root / "checker" / "bin" / "wpi.sh").read_text()
    except OSError:
        wpi_script = ""
    if "org.gradle.java.home=${JAVA21_HOME}" not in wpi_script:
        return DoctorCheck(name="wpi_gradle_jdk", status="ok", message="wpi.sh runs Gradle on JAVA_HOME.")
    try:
        jdk = resolve_jdk()
    except JdkResolutionError:
        jdk = None
    java21_home = os.environ.get("JAVA21_HOME")
    if (jdk is not None and jdk.major_version == 21) or java21_home:
        return DoctorCheck(
            name="wpi_gradle_jdk",
            status="ok",
            message="wpi.sh will run Gradle builds on JDK 21.",
            details={"java21_home": java21_home or (jdk.home if jdk else None)},
        )
    return DoctorCheck(
        name="wpi_gradle_jdk",
        status="error",
        message=(
            f"{cf_root.name}'s wpi.sh runs Gradle builds on JAVA21_HOME, which is not set. "
            "Set JAVA21_HOME to a JDK 21 (JAVA_HOME may stay newer) or use JDK 21 as JAVA_HOME."
        ),
    )


def _check_wpi_python() -> DoctorCheck:
    python = resolve_dljc_python()
    if python is None:
        return DoctorCheck(
            name="wpi_python",
            status="error",
            message=(
                "Whole-program inference needs a python3 with distutils (Python 3.11 or older, "
                "or newer with setuptools installed). Point ARODNAP_WPI_PYTHON at one."
            ),
        )
    return DoctorCheck(
        name="wpi_python",
        status="ok",
        message=f"Whole-program inference will use {python}.",
        details={"python": python},
    )


def _check_patch_binary() -> DoctorCheck:
    try:
        tool = discover_patch_tool(require_gnu=False, operation_label="arodnap doctor")
    except PatchToolError as exc:
        return DoctorCheck(
            name="patch_binary",
            status="error",
            message=str(exc),
        )

    if tool.flavor == "gnu":
        status: DoctorStatus = "ok"
        message = f"GNU patch is available via {tool.binary}."
    else:
        status = "warning"
        message = (
            f"{tool.flavor.upper()} patch is available via {tool.binary}. "
            "GNU patch is preferred for repair-stage dry-run validation."
        )

    return DoctorCheck(
        name="patch_binary",
        status=status,
        message=message,
        details={"binary": tool.binary, "flavor": tool.flavor, "version": tool.version},
    )


def _check_checker_framework_path(cf_root: Path) -> DoctorCheck:
    if cf_root.is_dir():
        return DoctorCheck(
            name="checker_framework_path",
            status="ok",
            message=f"Vendored Checker Framework path is present: {cf_root}",
            details={"path": cf_root},
        )

    return DoctorCheck(
        name="checker_framework_path",
        status="error",
        message=f"Vendored Checker Framework path is missing: {cf_root}",
        details={"path": cf_root},
    )


def _check_checker_framework_tools(cf_root: Path) -> DoctorCheck:
    required = {
        "wpi_sh": cf_root / "checker" / "bin" / "wpi.sh",
        "dljc": cf_root / "checker" / "bin" / ".do-like-javac" / "dljc",
        "javac": cf_root / "checker" / "bin" / "javac",
    }
    missing = [name for name, path in required.items() if not path.is_file()]
    if not missing:
        return DoctorCheck(
            name="checker_framework_tools",
            status="ok",
            message="Checker Framework helper tools are present.",
            details=required,
        )

    return DoctorCheck(
        name="checker_framework_tools",
        status="error",
        message=f"Checker Framework helper tools are missing: {', '.join(sorted(missing))}",
        details=required,
    )


def _check_plugin_jars(config: RunConfig) -> DoctorCheck:
    required = {
        "close_injector_jar": config.close_injector_jar,
        "owning_field_jar": config.owning_field_jar,
        "rlfixer_jar": config.rlfixer_jar,
        "rlpatcher_jar": config.rlpatcher_jar,
    }
    missing = [name for name, path in required.items() if not path.is_file()]
    if not missing:
        return DoctorCheck(
            name="plugin_jars",
            status="ok",
            message="Required plugin jars are present.",
            details=required,
        )

    return DoctorCheck(
        name="plugin_jars",
        status="error",
        message=f"Required plugin jars are missing: {', '.join(sorted(missing))}",
        details=required,
    )


def _check_repo_path(repo_root: Path) -> DoctorCheck:
    if not repo_root.exists():
        return DoctorCheck(
            name="repo_path",
            status="error",
            message=f"Repository path does not exist: {repo_root}",
            details={"repo_root": repo_root},
        )
    if not repo_root.is_dir():
        return DoctorCheck(
            name="repo_path",
            status="error",
            message=f"Repository path is not a directory: {repo_root}",
            details={"repo_root": repo_root},
        )

    return DoctorCheck(
        name="repo_path",
        status="ok",
        message=f"Repository path exists: {repo_root}",
        details={"repo_root": repo_root},
    )


def _run_repo_checks(
    config: RunConfig,
    *,
    workspace_root: Path,
) -> list[DoctorCheck]:
    checks: list[DoctorCheck] = []

    try:
        adapter = select_build_adapter(
            workspace_root,
            compile_target=config.compile_target,
            build_args=config.build_args,
        )
    except UnsupportedProjectError as exc:
        checks.append(
            DoctorCheck(
                name="adapter_selection",
                status="error",
                message=_normalize_workspace_message(str(exc), workspace_root=workspace_root, repo_root=config.repo_root),
                details={"repo_root": config.repo_root},
            )
        )
        return checks

    checks.append(
        DoctorCheck(
            name="adapter_selection",
            status="ok",
            message=f"Selected adapter {adapter.adapter_name} for build system {adapter.build_system}.",
            details={
                "adapter_name": adapter.adapter_name,
                "build_system": adapter.build_system,
            },
        )
    )

    try:
        project = adapter.inspect()
    except (MissingBuildToolError, UnsupportedProjectError, AdapterExecutionError) as exc:
        checks.append(
            DoctorCheck(
                name="repo_support",
                status="error",
                message=_normalize_workspace_message(str(exc), workspace_root=workspace_root, repo_root=config.repo_root),
                details={
                    "adapter_name": adapter.adapter_name,
                    "build_system": adapter.build_system,
                },
            )
        )
        return checks

    checks.append(
        DoctorCheck(
            name="repo_support",
            status="ok",
            message="Repository matches the supported v1 repo shape.",
            details=_project_details(project, workspace_root=workspace_root, repo_root=config.repo_root),
        )
    )
    checks.append(
        DoctorCheck(
            name="source_root",
            status="ok",
            message=f"Validated source root: {_display_repo_path(project.source_root, workspace_root=workspace_root, repo_root=config.repo_root)}",
            details={
                "source_root": _display_repo_path(project.source_root, workspace_root=workspace_root, repo_root=config.repo_root),
                "compiled_classes_root": _display_repo_path(
                    project.compiled_classes_root,
                    workspace_root=workspace_root,
                    repo_root=config.repo_root,
                ),
            },
        )
    )

    try:
        adapter.validate_compile(project)
    except (UnsupportedProjectError, AdapterExecutionError) as exc:
        checks.append(
            DoctorCheck(
                name="compile_target",
                status="error",
                message=_normalize_workspace_message(str(exc), workspace_root=workspace_root, repo_root=config.repo_root),
                details={
                    "compile_target": project.compile_target,
                    "build_tool": list(project.build_tool),
                    "build_tool_source": project.build_tool_source,
                },
            )
        )
        return checks

    checks.append(
        DoctorCheck(
            name="compile_target",
            status="ok",
            message=(
                f"Compile target '{project.compile_target}' validated using "
                f"{project.build_tool_source} Gradle."
            ),
            details={
                "compile_target": project.compile_target,
                "build_tool": list(project.build_tool),
                "build_tool_source": project.build_tool_source,
                "compiled_classes_root": _display_repo_path(
                    project.compiled_classes_root,
                    workspace_root=workspace_root,
                    repo_root=config.repo_root,
                ),
            },
        )
    )
    return checks


def _project_details(
    project: ProjectModel,
    *,
    workspace_root: Path,
    repo_root: Path,
) -> dict[str, Any]:
    return {
        "adapter_name": project.adapter_name,
        "build_system": project.build_system,
        "build_file": _display_repo_path(project.build_file, workspace_root=workspace_root, repo_root=repo_root),
        "build_tool": list(project.build_tool),
        "build_tool_source": project.build_tool_source,
        "compile_target": project.compile_target,
        "source_root": _display_repo_path(project.source_root, workspace_root=workspace_root, repo_root=repo_root),
        "compiled_classes_root": _display_repo_path(
            project.compiled_classes_root,
            workspace_root=workspace_root,
            repo_root=repo_root,
        ),
    }


def _display_repo_path(path: Path, *, workspace_root: Path, repo_root: Path) -> Path:
    try:
        relative = path.resolve().relative_to(workspace_root.resolve())
    except ValueError:
        return path.resolve()
    return repo_root.resolve() / relative


def _normalize_workspace_message(message: str, *, workspace_root: Path, repo_root: Path) -> str:
    return message.replace(str(workspace_root.resolve()), str(repo_root.resolve()))


def _print_summary(report: DoctorReport, report_path: Path) -> None:
    for check in report.checks:
        print(f"[{check.status.upper()}] {check.name}: {check.message}")
    print(f"Doctor report: {report_path}")


def _jsonable(value: Any) -> Any:
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, dict):
        return {key: _jsonable(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_jsonable(item) for item in value]
    return value


__all__ = ["DoctorCheck", "DoctorReport", "run_doctor"]
