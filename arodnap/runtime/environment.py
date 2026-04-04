from __future__ import annotations

import os
from os import PathLike
from typing import Mapping


def environment_with_overrides(
    overrides: Mapping[str, str | PathLike[str] | None],
) -> dict[str, str]:
    env = os.environ.copy()
    for key, value in overrides.items():
        if value is None:
            env.pop(key, None)
            continue
        env[key] = os.fspath(value)
    return env
