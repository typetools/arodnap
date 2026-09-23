from __future__ import annotations

import argparse
import os
from pathlib import Path

from arodnap.contracts import RunConfig, Timeouts


def build_run_config(
    args: argparse.Namespace,
    *,
    command: str,
) -> RunConfig:
    repo_root = Path(args.repo_root).resolve()
    out_dir = Path(args.out_dir).resolve()
    patch_dir = Path(args.patch_dir).resolve() if getattr(args, "patch_dir", None) else None

    return RunConfig(
        command=command,
        repo_root=repo_root,
        out_dir=out_dir,
        keep_workspace=args.keep_workspace,
        workspace_mode="copy",
        build_args=list(args.build_args),
        compile_target=args.compile_target,
        patch_dir=patch_dir,
        cf_root=resolve_cf_root(getattr(args, "checker_framework", None)),
        close_injector_jar=_plugin_jars_root() / "AutoCloseInjector-1.0-SNAPSHOT.jar",
        owning_field_jar=_plugin_jars_root() / "OwningFieldFixer-1.0-SNAPSHOT.jar",
        rlfixer_jar=_plugin_jars_root() / "RLFixer-1.0-SNAPSHOT.jar",
        rlpatcher_jar=_plugin_jars_root() / "RLPatcher-1.0-SNAPSHOT.jar",
        timeouts=Timeouts(build_seconds=900, analysis_seconds=1800, stage_seconds=900),
    )


CHECKER_FRAMEWORK_ENV = "ARODNAP_CHECKER_FRAMEWORK"
VENDORED_CHECKER_FRAMEWORK = "checker-framework-4.2.3"


def resolve_cf_root(cli_value: str | None = None) -> Path:
    """Checker Framework distribution to use: --checker-framework, then
    $ARODNAP_CHECKER_FRAMEWORK, then the vendored copy."""
    configured = cli_value or os.environ.get(CHECKER_FRAMEWORK_ENV)
    if configured:
        return Path(configured).expanduser().resolve()
    return _tool_repo_root() / "checker_framework" / VENDORED_CHECKER_FRAMEWORK


def _tool_repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def _plugin_jars_root() -> Path:
    return _tool_repo_root() / "restructure_plugins" / "prebuilt_plugin_jars"
