from __future__ import annotations

import argparse

import arodnap.doctor as doctor_module
from arodnap.orchestrator.config import build_run_config


def run(args: argparse.Namespace) -> int:
    return doctor_module.run_doctor(build_run_config(args, command="doctor"))
