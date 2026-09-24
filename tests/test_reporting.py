import json
import tempfile
import unittest
from pathlib import Path

from arodnap.contracts import ReanalyzeResult, StageResult
from arodnap.reporting.html import render_html_report
from arodnap.reporting.leaks import build_leak_report, match_runs, parse_diagnostics
from arodnap.reporting.summary import format_summary


def _leak(ws: Path, path: str, line: int, resource: str, type_: str, reason: str = "regular method exit") -> str:
    return (
        f"{ws}/{path}:{line}: warning: [required.method.not.called] $$ 4 $$ method close $$ {resource} $$ "
        f"{type_} $$ {reason} $$ ( 1, 2 ) $$ [required.method.not.called]\n        code();\n        ^\n"
    )


def _other(ws: Path, path: str, line: int) -> str:
    return (
        f"{ws}/{path}:{line}: warning: [missing.creates.mustcall.for] $$ 3 $$ exec $$ this $$ err $$ ( 3, 4 ) "
        "$$ [missing.creates.mustcall.for]\n"
    )


def _javac(ws: Path, path: str, line: int) -> str:
    return f"{ws}/{path}:{line}: warning: [removal] stop() in Thread has been deprecated and marked for removal\n"


class ParseAndMatchTest(unittest.TestCase):
    def test_warnings_are_matched_across_runs_by_content_not_line(self) -> None:
        ws = Path("/ws")
        before = parse_diagnostics(
            _leak(ws, "src/A.java", 10, "in", "java.io.InputStream")
            + _leak(ws, "src/A.java", 20, "in", "java.io.InputStream")
            + _leak(ws, "src/A.java", 30, "out", "java.io.OutputStream"),
            ws,
        )
        # A patch above shifted the lines; the first `in` leak was fixed.
        after = parse_diagnostics(
            _leak(ws, "src/A.java", 24, "in", "java.io.InputStream", reason="possible exceptional exit")
            + _leak(ws, "src/A.java", 34, "out", "java.io.OutputStream"),
            ws,
        )
        self.assertEqual([d.path for d in before], ["src/A.java"] * 3)
        self.assertEqual(before[0].fields[:3], ("method close", "in", "java.io.InputStream"))
        self.assertEqual(match_runs(before, after), {0: 0, 1: 2})

    def test_javac_warnings_have_no_checker_fields(self) -> None:
        [warning] = parse_diagnostics(_javac(Path("/ws"), "src/A.java", 3), Path("/ws"))
        self.assertEqual((warning.key, warning.from_checker), ("removal", False))


class LeakReportTest(unittest.TestCase):
    def test_statuses_stages_and_reasons(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir).resolve()
            ws = root / "ws"
            (ws / "src").mkdir(parents=True)
            out = root / "out"
            out.mkdir()

            def analysis(label: str, text: str) -> tuple[str, ReanalyzeResult]:
                diagnostics = out / f"{label}.txt"
                diagnostics.write_text("TOOL: rlc\nSTDERR:\n" + text)
                metadata = out / f"{label}-metadata.json"
                metadata.write_text(json.dumps({"source_root": str(ws / "src")}))
                return label, ReanalyzeResult(
                    workspace_root=ws, label=label, wpi_log_path=out / "wpi.log", inference_dir=out,
                    diagnostics_path=diagnostics, warning_count=0, source_files_file=out / "s",
                    app_classes_file=out / "a", classpath_entries_file=out / "c", adapter_metadata_path=metadata,
                )

            fixed_by_patch = _leak(ws, "src/demo/A.java", 10, "in", "java.io.FileInputStream")
            no_fix = _leak(ws, "src/demo/B.java", 5, "new FileReader(f)", "java.io.FileReader")
            unsafe = _leak(ws, "src/demo/C.java", 7, "conn.getInputStream()", "java.io.InputStream")
            wrapper = _leak(ws, "src/demo/W.java", 3, "field in", "java.io.InputStream")
            revealed = _leak(ws, "src/demo/Client.java", 12, "w", "demo.Wrapper")
            others = _other(ws, "src/demo/P.java", 4) + _javac(ws, "src/demo/P.java", 9)
            analyses = [
                analysis("initial", fixed_by_patch + no_fix + unsafe + wrapper + others),
                analysis("post_close_injector", fixed_by_patch + no_fix + unsafe + revealed + others),
                analysis("final", no_fix + _leak(ws, "src/demo/C.java", 8, "conn.getInputStream()",
                                                 "java.io.InputStream") + others),
            ]

            debug = out / "debug.txt"
            debug.write_text(
                "Index^Source File^Line Number^Matched Method^Duplicate^Unfixable\n"
                "0^demo/A.java^10^demo.A.m()V^false^false\n"
                "1^demo/B.java^5^demo.B.m()V^false^true\n"
                "2^demo/C.java^7^demo.C.m()V^false^false\n"
                "3^demo/Client.java^12^demo.Client.m()V^false^false\n"
            )
            patches = out / "patches"
            patches.mkdir()
            (patches / "patch-0001-src_demo_A.java-L10.patch").write_text("--- a\n+++ a\n@@ -1 +1 @@\n-x\n+y\n")
            (patches / "patch-0003-src_demo_Client.java-L12.patch").write_text("--- c\n+++ c\n")
            manifest = out / "patch_manifest.json"
            manifest.write_text(json.dumps({
                "patches": [
                    {"patch_file": str(patches / "patch-0001-src_demo_A.java-L10.patch"), "applied_to_workspace": True},
                    {"patch_file": str(patches / "patch-0003-src_demo_Client.java-L12.patch"),
                     "applied_to_workspace": True},
                ],
                "fixes": [
                    {"index": 1, "file": "demo/A.java", "line": 10, "outcome": "materialized"},
                    {"index": 2, "file": "demo/C.java", "line": 7, "outcome": "unsafe"},
                    {"index": 3, "file": "demo/Client.java", "line": 12, "outcome": "materialized"},
                ],
            }))
            close_patch = out / "close.patch"
            stage_results = {
                "close_injector": StageResult("close_injector", True, ["src/demo/W.java"], True,
                                              {"patch": str(close_patch)}, [], True),
                "rlfixer": StageResult("rlfixer", False, [], False, {"debug": str(debug)}, [], True),
                "rlpatcher": StageResult("rlpatcher", True, [], True, {"patch_manifest": str(manifest)}, [], True),
            }

            leaks = build_leak_report(
                analyses,
                workspace_root=ws,
                stage_for_label={"post_close_injector": "close_injector", "final": "rlpatcher"},
                rlfixer_label="post_close_injector",
                stage_results=stage_results,
            )

        by_file = {warning["file"]: warning for warning in leaks["warnings"]}
        self.assertEqual(by_file["src/demo/A.java"]["fixed_by"], "rlpatcher")
        self.assertTrue(by_file["src/demo/A.java"]["fix_patch"].endswith("patch-0001-src_demo_A.java-L10.patch"))
        self.assertEqual((by_file["src/demo/B.java"]["status"], by_file["src/demo/B.java"]["reason"]),
                         ("remaining", "rlfixer_unfixable"))
        self.assertEqual(by_file["src/demo/C.java"]["reason"], "unsafe")
        self.assertEqual(by_file["src/demo/C.java"]["line_after_repair"], 8)
        self.assertEqual(by_file["src/demo/W.java"]["fixed_by"], "close_injector")
        self.assertEqual(by_file["src/demo/W.java"]["fix_patch"], str(close_patch))
        self.assertEqual((by_file["src/demo/Client.java"]["first_seen"], by_file["src/demo/Client.java"]["fixed_by"]),
                         ("post_close_injector", "rlpatcher"))
        self.assertEqual(leaks["summary"], {
            "initial": 4,
            "found_during_repair": 1,
            "fixed": 3,
            "remaining": 2,
            "fixed_by_stage": {"close_injector": 1, "rlpatcher": 2},
            "remaining_by_reason": {"rlfixer_unfixable": 1, "unsafe": 1},
            "other_checker_warnings": 1,
            "javac_warnings": 1,
        })


class PresentationTest(unittest.TestCase):
    LEAKS = {
        "summary": {"initial": 2, "found_during_repair": 0, "fixed": 1, "remaining": 1,
                    "fixed_by_stage": {"rlpatcher": 1}, "remaining_by_reason": {"unsafe": 1},
                    "other_checker_warnings": 0, "javac_warnings": 2},
        "reasons": {"unsafe": "a fix would have to reorder code, which could change behavior"},
        "warnings": [
            {"id": 1, "file": "src/A.java", "line": 3, "first_seen": "initial", "finalizer": "close",
             "resource": "<script>alert(1)</script>", "type": "java.io.InputStream", "leak": "regular method exit",
             "status": "remaining", "fixed_by": None, "reason": "unsafe", "fix_patch": None},
        ],
        "other_warnings": [],
    }

    def test_html_escapes_source_text_and_links_nothing_external(self) -> None:
        html = render_html_report(leaks=self.LEAKS, repo_root=Path("/repo/demo"), patch_path=None, patch_dir=None,
                                  generated_at="2026-09-24T00:00:00Z")
        self.assertNotIn("<script>alert(1)</script>", html)
        self.assertIn("&lt;script&gt;alert(1)&lt;/script&gt;", html)
        self.assertIn("A fix would have to reorder code", html)
        self.assertNotIn("http://", html.replace("http://www.w3.org", ""))
        self.assertNotIn("https://", html)

    def test_terminal_summary(self) -> None:
        text = format_summary(self.LEAKS, repo_root=Path("/repo/demo"), patch_path=Path("/out/patches/arodnap.patch"),
                              patch_dir=Path("/out/patches"), changed_files=1, html_path=Path("/out/report.html"))
        self.assertEqual(text.splitlines(), [
            "Resource leaks: 2 found, 1 fixed, 1 remaining",
            "  Remaining because:",
            "    1  a fix would have to reorder code, which could change behavior",
            "  Also reported: 2 other warning(s) that are not resource leaks (see the report)",
            "Patch:  /out/patches/arodnap.patch (1 file(s))",
            "Apply:  arodnap apply --patch-dir /out/patches /repo/demo",
            "Report: /out/report.html",
        ])


if __name__ == "__main__":
    unittest.main()
