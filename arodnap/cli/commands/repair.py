from __future__ import annotations

import argparse

from arodnap.orchestrator import pipeline
from arodnap.orchestrator.config import build_run_config


def run(args: argparse.Namespace) -> int:
    return pipeline.run_repair(build_run_config(args, command="repair"))
