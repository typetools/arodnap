from __future__ import annotations

from arodnap.apply_support import apply_patch_bundle
from arodnap.contracts import RunConfig


def run_analyze(config: RunConfig) -> int:
    return 0


def run_infer(config: RunConfig) -> int:
    return 0


def run_repair(config: RunConfig) -> int:
    return 0


def run_apply(config: RunConfig) -> int:
    apply_patch_bundle(config)
    return 0
