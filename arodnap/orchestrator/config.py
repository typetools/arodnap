from __future__ import annotations

import argparse
import os
from pathlib import Path

from arodnap.contracts import RunConfig, Timeouts
from arodnap.resources import CHECKER_FRAMEWORK_DIRNAME, checker_framework_dir, jar


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
        close_injector_jar=jar("AutoCloseInjector-1.0-SNAPSHOT.jar"),
        owning_field_jar=jar("OwningFieldFixer-1.0-SNAPSHOT.jar"),
        rlfixer_jar=jar("RLFixer-1.0-SNAPSHOT.jar"),
        rlpatcher_jar=jar("RLPatcher-1.0-SNAPSHOT.jar"),
        timeouts=Timeouts(
            build_seconds=getattr(args, "build_timeout", None),
            analysis_seconds=getattr(args, "analysis_timeout", None),
            stage_seconds=getattr(args, "stage_timeout", None),
        ),
        build_command=tuple(getattr(args, "build_command", None) or ()),
        field_transformations=getattr(args, "field_transformations", None) or "resources",
    )


CHECKER_FRAMEWORK_ENV = "ARODNAP_CHECKER_FRAMEWORK"
VENDORED_CHECKER_FRAMEWORK = CHECKER_FRAMEWORK_DIRNAME


def resolve_cf_root(cli_value: str | None = None) -> Path:
    """Checker Framework distribution to use: --checker-framework, then
    $ARODNAP_CHECKER_FRAMEWORK, then the vendored copy."""
    configured = cli_value or os.environ.get(CHECKER_FRAMEWORK_ENV)
    if configured:
        return Path(configured).expanduser().resolve()
    return checker_framework_dir()
