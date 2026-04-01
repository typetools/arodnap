from __future__ import annotations

import argparse
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
        cf_root=_tool_repo_root() / "checker_framework" / "checker-framework-3.49.0",
        close_injector_jar=_plugin_jars_root() / "AutoCloseInjector-1.0-SNAPSHOT.jar",
        owning_field_jar=_plugin_jars_root() / "OwningFieldFixer-1.0-SNAPSHOT.jar",
        rlpatcher_jar=_plugin_jars_root() / "RLPatcher-1.0-SNAPSHOT.jar",
        timeouts=Timeouts(build_seconds=900, analysis_seconds=1800, stage_seconds=900),
    )


def _tool_repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def _plugin_jars_root() -> Path:
    return _tool_repo_root() / "restructure_plugins" / "prebuilt_plugin_jars"
