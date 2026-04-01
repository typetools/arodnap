from __future__ import annotations

import argparse
import sys

from arodnap.apply_support import ApplyError
from arodnap.orchestrator import pipeline
from arodnap.orchestrator.config import build_run_config


def run(args: argparse.Namespace) -> int:
    try:
        return pipeline.run_apply(build_run_config(args, command="apply"))
    except ApplyError as exc:
        print(str(exc), file=sys.stderr)
        return 1
