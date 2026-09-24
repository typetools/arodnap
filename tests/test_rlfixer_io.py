import json
import unittest
from pathlib import Path

from arodnap.stages.rlfixer_io import (
    CheckerWarning,
    FixSuggestion,
    build_rlpatcher_prompt,
    has_nothing_to_do,
    match_fixes_to_warnings,
    parse_checker_warnings,
    parse_debug_fixable,
    parse_fix_suggestions,
    rlfixer_warnings_argument,
    select_fixable_suggestions,
)

_ROOT = Path("/ws/src/main/java")


class RLFixerIoTest(unittest.TestCase):
    def test_parse_checker_warnings_splits_blocks(self) -> None:
        text = (
            "/ws/src/main/java/a/A.java:3: warning: (required.method.not.called) first\n"
            "  context\n"
            "/ws/src/main/java/a/B.java:7: warning: (missing.creates.mustcall.for) second\n"
            "2 warnings\n"
        )
        warnings = parse_checker_warnings(text)
        self.assertEqual([(w.filepath, w.line_number) for w in warnings], [
            ("/ws/src/main/java/a/A.java", 3),
            ("/ws/src/main/java/a/B.java", 7),
        ])
        self.assertIn("context", warnings[0].message)

    def test_warnings_argument_keeps_only_leaks_and_flags_owning_overwrites(self) -> None:
        warnings = [
            CheckerWarning("/ws/src/main/java/a/A.java", 3, "/ws/src/main/java/a/A.java:3: warning: (required.method.not.called) x"),
            CheckerWarning("/ws/src/main/java/a/A.java", 5, "/ws/src/main/java/a/A.java:5: warning: (missing.creates.mustcall.for) x"),
            CheckerWarning(
                "/ws/src/main/java/a/B.java",
                9,
                "/ws/src/main/java/a/B.java:9: warning: (required.method.not.called) $$ Non-final owning field might be overwritten",
            ),
            CheckerWarning("/elsewhere/C.java", 1, "/elsewhere/C.java:1: warning: (required.method.not.called) x"),
        ]
        argument, skipped = rlfixer_warnings_argument(warnings, source_root=_ROOT)
        self.assertEqual(argument, "a/A.java,3,None,False#a/B.java,9,None,True#")
        self.assertEqual([w.filepath for w in skipped], ["/elsewhere/C.java"])

    def test_warning_key_formats_of_checker_framework_3_and_4_are_accepted(self) -> None:
        warnings = [
            CheckerWarning("/ws/src/main/java/a/A.java", 1, "/ws/src/main/java/a/A.java:1: warning: (required.method.not.called) x"),
            CheckerWarning("/ws/src/main/java/a/A.java", 2, "/ws/src/main/java/a/A.java:2: warning: [required.method.not.called] x"),
            CheckerWarning(
                "/ws/src/main/java/a/A.java", 3, "/ws/src/main/java/a/A.java:3: warning: [resourceleak:required.method.not.called] x"
            ),
            CheckerWarning("/ws/src/main/java/a/A.java", 4, "/ws/src/main/java/a/A.java:4: warning: [missing.creates.mustcall.for] x"),
        ]
        argument, _ = rlfixer_warnings_argument(warnings, source_root=_ROOT)
        self.assertEqual(argument, "a/A.java,1,None,False#a/A.java,2,None,False#a/A.java,3,None,False#")

    def test_fix_suggestions_use_source_root_relative_paths(self) -> None:
        text = (
            "\nSOURCE LEVEL FIXES\n\n"
            "0] /ws/src/main/java/a/A.java; Line number 3\n"
            "vim +3 /ws/src/main/java/a/A.java\n\n+++ Add try\n"
            "--------------------------------------------\n"
        )
        [suggestion] = parse_fix_suggestions(text, source_root=_ROOT)
        self.assertEqual(suggestion.relpath, "a/A.java")
        self.assertEqual(suggestion.line_number, 3)
        self.assertIn("+++ Add try", suggestion.suggestion)

    def test_debug_table_filters_unfixable_duplicate_and_unmatched_rows(self) -> None:
        debug = "\n".join(
            [
                "Index^Source File^Line Number^Matched Method^MatchedInstruction^Aliases^Classification^Duplicate^Unfixable^Comments",
                "0^a/A.java^3^m()^i^NULL^INVOKE,^false^false^Normal Fix;",
                "1^a/A.java^9^m()^i^NULL^FIELD,^false^true^Field escape;",
                "2^a/B.java^4^m()^i^NULL^INVOKE,^true^false^dup",
                "3^a/C.java^5^UNMATCHED^UNMATCHED^NULL^NULL^NULL^true^NULL",
            ]
        )
        self.assertEqual(parse_debug_fixable(debug), {("a/A.java", 3)})
        self.assertEqual(parse_debug_fixable(""), set())

    def test_selection_and_matching(self) -> None:
        keep = FixSuggestion("/ws/src/main/java/a/A.java", "a/A.java", 3, "s1")
        drop = FixSuggestion("/ws/src/main/java/a/A.java", "a/A.java", 9, "s2")
        self.assertEqual(select_fixable_suggestions([keep, drop], {("a/A.java", 3)}), [keep])
        self.assertEqual(select_fixable_suggestions([keep, drop], set()), [keep, drop])

        msg = "/ws/src/main/java/a/A.java:3: warning: [required.method.not.called] $$ leak"
        warning = CheckerWarning("/ws/src/main/java/a/A.java", 3, msg)
        self.assertEqual(match_fixes_to_warnings([keep, drop], [warning]), [(keep, warning)])
        self.assertEqual(
            json.loads(build_rlpatcher_prompt(warning, keep)),
            {"CF Leaks": [msg], "RLFixer hint": ["s1"]},
        )

    def test_suggestions_pair_with_the_leak_warning_not_another_warning_on_the_same_line(self) -> None:
        # Seen on Apache Ivy: `in = PGPUtil.getDecoderStream(in);` has both.
        path = "/ws/src/main/java/a/A.java"
        leak = CheckerWarning(path, 7, f"{path}:7: warning: [required.method.not.called] $$ leak")
        assignment = CheckerWarning(path, 7, f"{path}:7: warning: [assignment] $$ 2 $$ incompatible types")
        fix = FixSuggestion(path, "a/A.java", 7, "s")
        self.assertEqual(match_fixes_to_warnings([fix], [leak, assignment]), [(fix, leak)])
        self.assertEqual(match_fixes_to_warnings([fix], [assignment]), [])

    def test_suggestions_without_a_source_edit(self) -> None:
        def suggestion(text: str) -> FixSuggestion:
            return FixSuggestion("/ws/A.java", "A.java", 1, text)

        nothing = (
            "vim +135 /ws/A.java\n\n"
            "+++ NOTE: Resource escapes via return statement and needs to be closed in the callers of resolveEntity\n"
            "+++ Nothing to be done. No callers found for method with resource return"
        )
        self.assertTrue(has_nothing_to_do(suggestion(nothing)))
        self.assertFalse(has_nothing_to_do(suggestion("+++ Add following code above line:3 (A.java)\ntry{")))
        self.assertFalse(has_nothing_to_do(suggestion(nothing + "\n+++ Delete Line number 9 (A.java)")))


if __name__ == "__main__":
    unittest.main()
