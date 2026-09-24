from __future__ import annotations

import argparse
import sys
from collections.abc import Sequence
from pathlib import Path

from arodnap.cli.commands import analyze, apply, doctor, infer, repair


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="arodnap")
    subparsers = parser.add_subparsers(dest="command", required=True)

    analyze_parser = subparsers.add_parser("analyze")
    _add_shared_arguments(analyze_parser)
    analyze_parser.set_defaults(handler=analyze.run)

    infer_parser = subparsers.add_parser("infer")
    _add_shared_arguments(infer_parser)
    infer_parser.set_defaults(handler=infer.run)

    repair_parser = subparsers.add_parser("repair")
    _add_shared_arguments(repair_parser)
    repair_parser.set_defaults(handler=repair.run)

    apply_parser = subparsers.add_parser("apply")
    _add_shared_arguments(apply_parser)
    apply_parser.add_argument("--patch-dir", required=True)
    apply_parser.set_defaults(handler=apply.run)

    doctor_parser = subparsers.add_parser("doctor")
    _add_shared_arguments(doctor_parser)
    doctor_parser.set_defaults(handler=doctor.run)

    return parser


def main(argv: Sequence[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    # Everything after "--" is the project's build command: arodnap repair <repo> -- ./build.sh
    build_command: list[str] = []
    if "--" in argv:
        split = argv.index("--")
        argv, build_command = argv[:split], argv[split + 1 :]
    parser = build_parser()
    args = parser.parse_args(argv)
    if build_command and args.command == "apply":
        parser.error("apply does not take a build command")
    args.build_command = build_command
    return args.handler(args)


def _add_shared_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--out-dir", default=str(Path.cwd() / "arodnap-out"))
    parser.add_argument("--keep-workspace", action="store_true")
    parser.add_argument("--build-args", action="append", default=[])
    parser.add_argument("--compile-target")
    parser.add_argument(
        "--checker-framework",
        help=(
            "Checker Framework distribution directory (the one containing checker/bin/wpi.sh). "
            "Defaults to $ARODNAP_CHECKER_FRAMEWORK, then the bundled 4.2.3."
        ),
    )
    limits = parser.add_argument_group(
        "time limits",
        "Each limit applies to every single command of its kind and kills it (with its child "
        "processes) when exceeded. There are no limits by default.",
    )
    limits.add_argument("--build-timeout", type=_seconds, metavar="SECONDS",
                        help="limit for the project's build while Arodnap captures it")
    limits.add_argument("--analysis-timeout", type=_seconds, metavar="SECONDS",
                        help="limit for each Checker Framework run (every inference iteration, "
                             "every leak check) and the analysis compile")
    limits.add_argument("--stage-timeout", type=_seconds, metavar="SECONDS",
                        help="limit for each repair tool run (close injector, owning-field fixer, "
                             "RLFixer, and RLPatcher per suggestion)")
    parser.add_argument("repo_root")


def _seconds(value: str) -> int:
    try:
        seconds = int(value)
    except ValueError:
        raise argparse.ArgumentTypeError(f"expected a whole number of seconds, got {value!r}") from None
    if seconds <= 0:
        raise argparse.ArgumentTypeError(f"expected a positive number of seconds, got {seconds}")
    return seconds


if __name__ == "__main__":
    raise SystemExit(main())
