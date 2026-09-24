"""A self-contained HTML page for a repair run: what was fixed, what remains and why, and
the patch. It has no external resources, so it can be opened offline or attached to a
CI run."""

from __future__ import annotations

from html import escape
from pathlib import Path
import shlex
from typing import Any

STAGE_NAMES = {
    "close_injector": "close injection",
    "owning_field": "owning-field repair",
    "rlpatcher": "RLFixer + RLPatcher",
}


def write_html_report(
    path: Path,
    *,
    leaks: dict[str, Any],
    repo_root: Path,
    patch_path: Path | None,
    patch_dir: Path | None,
    generated_at: str,
) -> Path:
    path.write_text(render_html_report(
        leaks=leaks, repo_root=repo_root, patch_path=patch_path, patch_dir=patch_dir, generated_at=generated_at
    ))
    return path


def render_html_report(
    *,
    leaks: dict[str, Any],
    repo_root: Path,
    patch_path: Path | None,
    patch_dir: Path | None,
    generated_at: str,
) -> str:
    summary = leaks["summary"]
    warnings = leaks["warnings"]
    reasons = leaks["reasons"]
    patch_text = patch_path.read_text(errors="replace") if patch_path and patch_path.is_file() else ""
    patched_files = _split_patch(patch_text)

    parts = [_HEAD.format(title=escape(f"Arodnap: {repo_root.name}"))]
    parts.append(f"<header><h1>Arodnap report: {escape(repo_root.name)}</h1>"
                 f"<p class='muted'>{escape(str(repo_root))} · {escape(generated_at)}</p></header>")

    total = summary["fixed"] + summary["remaining"]
    parts.append("<section class='cards'>")
    parts.append(_card(str(total), "resource leaks found"))
    parts.append(_card(str(summary["fixed"]), "fixed", "good"))
    parts.append(_card(str(summary["remaining"]), "remaining", "warn" if summary["remaining"] else "good"))
    parts.append(_card(str(len(patched_files)), "files changed"))
    parts.append("</section>")

    if patch_text and patch_dir is not None:
        command = f"arodnap apply --patch-dir {shlex.quote(str(patch_dir))} {shlex.quote(str(repo_root))}"
        parts.append(
            "<section><h2>Apply the fixes</h2>"
            f"<pre class='cmd'>{escape(command)}</pre>"
            "<p class='muted'>This applies every fix below to your working tree; the patch was verified to "
            "apply and compile on a clean copy. Review the result with <code>git diff</code> and drop any "
            "change you do not want with <code>git checkout -p</code> or your IDE.</p></section>"
        )

    if summary["remaining"]:
        parts.append("<section><h2>Why some leaks remain</h2><table class='reasons'>")
        for code, count in summary["remaining_by_reason"].items():
            parts.append(f"<tr><td class='num'>{count}</td><td>{escape(reasons.get(code, code))}</td></tr>")
        parts.append("</table></section>")

    parts.append("<section><h2>Leaks</h2>")
    parts.append(
        "<div class='filters'>"
        "<button class='on' data-filter='all'>All</button>"
        "<button data-filter='fixed'>Fixed</button>"
        "<button data-filter='remaining'>Remaining</button>"
        "<input type='search' placeholder='Filter by file or resource' aria-label='Filter by file or resource'>"
        "</div>"
    )
    for warning in warnings:
        parts.append(_warning_row(warning, reasons))
    parts.append("</section>")

    if patched_files:
        parts.append("<section><h2>All changes</h2>")
        for filename, diff in patched_files:
            parts.append(f"<details><summary><code>{escape(filename)}</code></summary>{_diff(diff)}</details>")
        parts.append("</section>")

    others = leaks.get("other_warnings", [])
    if others:
        counts: dict[str, int] = {}
        for other in others:
            counts[other["kind"]] = counts.get(other["kind"], 0) + 1
        parts.append("<section><h2>Other warnings</h2><p class='muted'>Reported during analysis but not "
                     "resource leaks that Arodnap repairs.</p><table class='reasons'>")
        for kind, count in sorted(counts.items(), key=lambda item: (-item[1], item[0])):
            parts.append(f"<tr><td class='num'>{count}</td><td><code>{escape(kind)}</code></td></tr>")
        parts.append("</table></section>")

    parts.append(_SCRIPT)
    parts.append("</main></body></html>\n")
    return "\n".join(parts)


def _card(value: str, label: str, tone: str = "") -> str:
    return f"<div class='card {tone}'><div class='value'>{escape(value)}</div><div>{escape(label)}</div></div>"


def _warning_row(warning: dict[str, Any], reasons: dict[str, str]) -> str:
    status = warning["status"]
    location = f"{warning['file']}:{warning['line']}"
    if status == "fixed":
        badge = f"<span class='badge good'>fixed</span>"
        outcome = f"Fixed by {escape(STAGE_NAMES.get(warning['fixed_by'], warning['fixed_by']))}."
    else:
        badge = "<span class='badge warn'>remaining</span>"
        text = reasons.get(warning["reason"], warning["reason"] or "")
        outcome = escape(text[:1].upper() + text[1:]) + "."
    body = [f"<p>{outcome}</p>"]
    body.append(
        "<dl>"
        f"<dt>Resource</dt><dd><code>{escape(warning['resource'])}</code> "
        f"(<code>{escape(warning['type'])}</code>) is never passed to <code>{escape(warning['finalizer'])}()</code> "
        "on some path.</dd>"
        f"<dt>Checker says</dt><dd>{escape(warning['leak'] or '')}</dd>"
        "</dl>"
    )
    if warning.get("first_seen") and warning["first_seen"] != "initial":
        body.append("<p class='muted'>Reported only after an earlier repair step (for example, making a "
                    "wrapper class closeable exposes the leaks in its callers).</p>")
    patch = warning.get("fix_patch")
    if patch and Path(patch).is_file():
        body.append(_diff(Path(patch).read_text(errors="replace")))
    search = f"{location} {warning['resource']} {warning['type']}".lower()
    return (
        f"<details class='leak' data-status='{status}' data-search='{escape(search)}'>"
        f"<summary>{badge}<code>{escape(location)}</code><span class='res'>{escape(warning['resource'])}</span>"
        f"</summary><div class='body'>{''.join(body)}</div></details>"
    )


def _split_patch(text: str) -> list[tuple[str, str]]:
    files: list[tuple[str, list[str]]] = []
    for line in text.splitlines():
        if line.startswith("--- "):
            files.append(("", [line]))
        elif line.startswith("+++ ") and files and not files[-1][0]:
            files[-1] = (line[4:].split("\t")[0].strip(), files[-1][1] + [line])
        elif files:
            files[-1][1].append(line)
    return [(name, "\n".join(lines)) for name, lines in files]


def _diff(text: str) -> str:
    rows = []
    for line in text.splitlines():
        if line.startswith(("+++", "---")):
            continue
        tone = "add" if line.startswith("+") else "del" if line.startswith("-") else "hunk" if line.startswith("@@") else ""
        rows.append(f"<span class='{tone}'>{escape(line) or ' '}</span>")
    # Each line is a block element, so no newline characters between them inside <pre>.
    return f"<pre class='diff'>{''.join(rows)}</pre>"


_HEAD = """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<style>
:root {{ --bg:#ffffff; --fg:#1f2328; --muted:#59636e; --border:#d1d9e0; --panel:#f6f8fa;
  --good:#1a7f37; --warn:#9a6700; --add-bg:#dafbe1; --del-bg:#ffebe9; --hunk:#0969da; }}
@media (prefers-color-scheme: dark) {{ :root {{ --bg:#0d1117; --fg:#e6edf3; --muted:#9198a1; --border:#3d444d;
  --panel:#151b23; --good:#3fb950; --warn:#d29922; --add-bg:#12261e; --del-bg:#2d1214; --hunk:#4493f8; }} }}
body {{ margin:0; background:var(--bg); color:var(--fg);
  font:15px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Helvetica,Arial,sans-serif; }}
main {{ max-width:1100px; margin:0 auto; padding:24px 16px 64px; }}
h1 {{ font-size:24px; margin:0 0 4px; }} h2 {{ font-size:18px; margin:32px 0 12px; }}
.muted {{ color:var(--muted); }}
code, pre {{ font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace; font-size:13px; }}
.cards {{ display:grid; grid-template-columns:repeat(auto-fit,minmax(150px,1fr)); gap:12px; margin-top:20px; }}
.card {{ border:1px solid var(--border); border-radius:8px; padding:12px 16px; background:var(--panel); }}
.card .value {{ font-size:28px; font-weight:600; }}
.card.good .value, .badge.good {{ color:var(--good); }} .card.warn .value, .badge.warn {{ color:var(--warn); }}
.cmd {{ background:var(--panel); border:1px solid var(--border); border-radius:6px; padding:10px 12px;
  overflow-x:auto; }}
table.reasons td {{ padding:3px 12px 3px 0; vertical-align:top; }} td.num {{ text-align:right; font-weight:600; }}
.filters {{ display:flex; flex-wrap:wrap; gap:8px; margin-bottom:12px; }}
.filters button {{ border:1px solid var(--border); background:var(--panel); color:var(--fg); border-radius:6px;
  padding:4px 12px; cursor:pointer; font:inherit; }}
.filters button.on {{ border-color:var(--hunk); color:var(--hunk); }}
.filters input {{ flex:1; min-width:180px; border:1px solid var(--border); border-radius:6px; padding:4px 8px;
  background:var(--bg); color:var(--fg); font:inherit; }}
details {{ border:1px solid var(--border); border-radius:6px; margin:6px 0; background:var(--bg); }}
details > summary {{ cursor:pointer; padding:6px 10px; display:flex; gap:10px; align-items:baseline;
  flex-wrap:wrap; }}
details .body {{ padding:0 12px 8px; }}
.badge {{ font-size:12px; font-weight:600; text-transform:uppercase; min-width:76px; }}
.res {{ color:var(--muted); }}
summary > *, dd, .muted {{ min-width:0; overflow-wrap:anywhere; }}
dl {{ display:grid; grid-template-columns:max-content 1fr; gap:4px 12px; }} dt {{ color:var(--muted); }}
dd {{ margin:0; overflow-wrap:anywhere; }}
pre.diff {{ background:var(--panel); border:1px solid var(--border); border-radius:6px; padding:8px 0;
  overflow-x:auto; }}
pre.diff span {{ display:block; padding:0 12px; white-space:pre; }}
pre.diff .add {{ background:var(--add-bg); }} pre.diff .del {{ background:var(--del-bg); }}
pre.diff .hunk {{ color:var(--hunk); }}
</style></head><body><main>"""

_SCRIPT = """<script>
(function () {
  var filter = "all", query = "";
  var rows = document.querySelectorAll("details.leak");
  function update() {
    rows.forEach(function (row) {
      var show = (filter === "all" || row.dataset.status === filter) &&
                 (!query || row.dataset.search.indexOf(query) !== -1);
      row.style.display = show ? "" : "none";
    });
  }
  document.querySelectorAll(".filters button").forEach(function (button) {
    button.addEventListener("click", function () {
      document.querySelectorAll(".filters button").forEach(function (b) { b.classList.remove("on"); });
      button.classList.add("on");
      filter = button.dataset.filter;
      update();
    });
  });
  var input = document.querySelector(".filters input");
  if (input) input.addEventListener("input", function () { query = input.value.toLowerCase(); update(); });
})();
</script>"""
