from __future__ import annotations

import argparse

from arodnap.orchestrator.config import build_run_config
from arodnap.orchestrator import pipeline


def run(args: argparse.Namespace) -> int:
    return pipeline.run_infer(build_run_config(args, command="infer"))
