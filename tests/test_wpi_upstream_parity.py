"""Keeps Arodnap's WPI loop in step with the Checker Framework's own.

Arodnap runs whole-program inference itself (arodnap/analysis/wpi_runner.py) instead of
through the Checker Framework's wpi.sh. The loop it mirrors is do-like-javac's WPI tool,
shipped inside the Checker Framework distribution at
checker/bin/.do-like-javac/do_like_javac/tools/wpi.py.

When the bundled Checker Framework is upgraded, these tests fail if that tool changed:

1. Diff the old and new tools/wpi.py.
2. Port any change to the inference loop or its flags into wpi_runner.py (build-system
   handling, JDK selection and delombok are wpi.sh/dljc concerns Arodnap does not need).
3. Rerun ARODNAP_E2E=1 python -m unittest tests.test_e2e_real.
4. Update UPSTREAM_WPI_TOOL_SHA256 and, if needed, the flag sets below.
"""

import hashlib
import re
import unittest

from arodnap.analysis.wpi_runner import WPI_ITERATION_FLAGS
from arodnap.orchestrator.config import resolve_cf_root

# checker-framework-4.2.3/checker/bin/.do-like-javac/do_like_javac/tools/wpi.py
UPSTREAM_WPI_TOOL_SHA256 = "e7ef6913d73c46644d52ab837a61d8c9827179a393dfea56adcbc2a1ef50469f"

# Checker options the upstream loop passes and how Arodnap treats each one.
UPSTREAM_FLAGS_ARODNAP_PASSES = {"-Ainfer=ajava", "-Awarns", "-Aajava="}
UPSTREAM_FLAGS_ARODNAP_SKIPS = {
    "-Astubs=": "only when dljc is given --stubs; wpi.sh does not pass it",
    "-AsuppressWarnings=type.anno.before.modifier": "only for delombok'd sources",
}


def _bundled_wpi_tool():
    return resolve_cf_root() / "checker" / "bin" / ".do-like-javac" / "do_like_javac" / "tools" / "wpi.py"


class WpiUpstreamParityTest(unittest.TestCase):
    def test_bundled_upstream_wpi_loop_is_the_reviewed_version(self) -> None:
        digest = hashlib.sha256(_bundled_wpi_tool().read_bytes()).hexdigest()
        self.assertEqual(
            digest,
            UPSTREAM_WPI_TOOL_SHA256,
            "The Checker Framework's WPI loop (do-like-javac tools/wpi.py) changed. Review it against "
            "arodnap/analysis/wpi_runner.py as described in this test's docstring.",
        )

    def test_every_upstream_checker_flag_is_accounted_for(self) -> None:
        upstream_flags = set(re.findall(r'"(-A[A-Za-z]+(?:=[A-Za-z.]*)?)', _bundled_wpi_tool().read_text()))
        self.assertEqual(upstream_flags, UPSTREAM_FLAGS_ARODNAP_PASSES | set(UPSTREAM_FLAGS_ARODNAP_SKIPS))
        self.assertEqual(set(WPI_ITERATION_FLAGS) | {"-Aajava="}, UPSTREAM_FLAGS_ARODNAP_PASSES)


if __name__ == "__main__":
    unittest.main()
