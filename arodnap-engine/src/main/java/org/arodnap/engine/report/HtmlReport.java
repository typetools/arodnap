package org.arodnap.engine.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A self-contained HTML page for a repair run: what was fixed, what remains and why, and the patch.
 * It has no external resources, so it can be opened offline or attached to a CI run.
 */
public final class HtmlReport {
    private static final Map<String, String> STAGE_NAMES = Map.of("close_injector", "close injection", "owning_field",
            "owning-field repair", "rlpatcher", "RLFixer + RLPatcher");

    private HtmlReport() {}

    public static Path write(Path file, Map<String, Object> leaks, Path repoRoot, Optional<Path> patch, String applyCommand, String generatedAt)
            throws IOException {
        Files.writeString(file, render(leaks, repoRoot, patch, applyCommand, generatedAt), StandardCharsets.UTF_8);
        return file;
    }

    @SuppressWarnings("unchecked")
    static String render(Map<String, Object> leaks, Path repoRoot, Optional<Path> patch, String applyCommand, String generatedAt)
            throws IOException {
        Map<String, Object> summary = (Map<String, Object>) leaks.get("summary");
        List<Map<String, Object>> warnings = (List<Map<String, Object>>) leaks.get("warnings");
        Map<String, String> reasons = (Map<String, String>) leaks.get("reasons");
        String patchText = patch.isPresent() && Files.isRegularFile(patch.get()) ? read(patch.get()) : "";
        List<String[]> patchedFiles = splitPatch(patchText);
        String name = repoRoot.getFileName() == null ? repoRoot.toString() : repoRoot.getFileName().toString();

        List<String> parts = new ArrayList<>();
        parts.add(HEAD.replace("{title}", escape("Arodnap: " + name)));
        parts.add("<header><h1>Arodnap report: " + escape(name) + "</h1><p class='muted'>" + escape(repoRoot.toString()) + " · "
                + escape(generatedAt) + "</p></header>");
        long fixed = Summary.number(summary.get("fixed"));
        long remaining = Summary.number(summary.get("remaining"));
        parts.add("<section class='cards'>");
        parts.add(card(Long.toString(fixed + remaining), "resource leaks found", ""));
        parts.add(card(Long.toString(fixed), "fixed", "good"));
        parts.add(card(Long.toString(remaining), "remaining", remaining > 0 ? "warn" : "good"));
        parts.add(card(Integer.toString(patchedFiles.size()), "files changed", ""));
        parts.add("</section>");

        if (!patchText.isEmpty()) {
            parts.add("<section><h2>Apply the fixes</h2><pre class='cmd'>" + escape(applyCommand) + "</pre>"
                    + "<p class='muted'>This applies every fix below to your working tree; the patch was verified to "
                    + "apply and compile on a clean copy. Review the result with <code>git diff</code> and drop any "
                    + "change you do not want with <code>git checkout -p</code> or your IDE.</p></section>");
        }
        if (remaining > 0) {
            parts.add("<section><h2>Why some leaks remain</h2><table class='reasons'>");
            ((Map<String, Object>) summary.get("remaining_by_reason")).forEach((code, count) -> parts.add(
                    "<tr><td class='num'>" + count + "</td><td>" + escape(reasons.getOrDefault(code, code)) + "</td></tr>"));
            parts.add("</table></section>");
        }

        parts.add("<section><h2>Leaks</h2>");
        parts.add("<div class='filters'><button class='on' data-filter='all'>All</button><button data-filter='fixed'>Fixed</button>"
                + "<button data-filter='remaining'>Remaining</button><input type='search' placeholder='Filter by file or resource' "
                + "aria-label='Filter by file or resource'></div>");
        for (Map<String, Object> warning : warnings) {
            parts.add(warningRow(warning, reasons));
        }
        parts.add("</section>");

        Map<String, Object> fieldChanges = (Map<String, Object>) leaks.getOrDefault("field_changes", Map.of());
        List<Map<String, Object>> changes = (List<Map<String, Object>>) fieldChanges.getOrDefault("changes", List.of());
        if (!changes.isEmpty()) {
            parts.add("<section><h2>Field changes</h2><p class='muted'>Private resource fields made <code>final</code> or turned into "
                    + "local variables before the analysis. They do not change behavior and make ownership explicit, which removes "
                    + "false leak warnings.</p><table class='reasons'>");
            for (Map<String, Object> change : changes) {
                String what = "final".equals(change.get("change")) ? "made final" : "turned into a local variable";
                parts.add("<tr><td><code>" + escape(String.valueOf(change.get("file"))) + ":" + change.get("line") + "</code></td><td><code>"
                        + escape(String.valueOf(change.get("field"))) + "</code> " + what + "</td></tr>");
            }
            parts.add("</table>");
            Object fieldPatch = fieldChanges.get("patch");
            if (fieldPatch instanceof String text && Files.isRegularFile(Path.of(text))) {
                parts.add("<details><summary>Diff</summary>" + diff(read(Path.of(text))) + "</details>");
            }
            parts.add("</section>");
        }
        if (!patchedFiles.isEmpty()) {
            parts.add("<section><h2>All changes</h2>");
            for (String[] file : patchedFiles) {
                parts.add("<details><summary><code>" + escape(file[0]) + "</code></summary>" + diff(file[1]) + "</details>");
            }
            parts.add("</section>");
        }
        List<Map<String, Object>> others = (List<Map<String, Object>>) leaks.getOrDefault("other_warnings", List.of());
        if (!others.isEmpty()) {
            Map<String, Integer> counts = new TreeMap<>();
            others.forEach(other -> counts.merge(String.valueOf(other.get("kind")), 1, Integer::sum));
            parts.add("<section><h2>Other warnings</h2><p class='muted'>Reported during analysis but not resource leaks that Arodnap "
                    + "repairs.</p><table class='reasons'>");
            counts.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(entry -> -entry.getValue()).thenComparing(Map.Entry::getKey))
                    .forEach(entry -> parts.add("<tr><td class='num'>" + entry.getValue() + "</td><td><code>" + escape(entry.getKey())
                            + "</code></td></tr>"));
            parts.add("</table></section>");
        }
        parts.add(SCRIPT);
        parts.add("</main></body></html>\n");
        return String.join("\n", parts);
    }

    private static String card(String value, String label, String tone) {
        return "<div class='card " + tone + "'><div class='value'>" + escape(value) + "</div><div>" + escape(label) + "</div></div>";
    }

    private static String warningRow(Map<String, Object> warning, Map<String, String> reasons) throws IOException {
        String status = String.valueOf(warning.get("status"));
        String location = warning.get("file") + ":" + warning.get("line");
        String badge;
        String outcome;
        if (status.equals("fixed")) {
            badge = "<span class='badge good'>fixed</span>";
            String stage = String.valueOf(warning.get("fixed_by"));
            outcome = "Fixed by " + escape(STAGE_NAMES.getOrDefault(stage, stage)) + ".";
        } else {
            badge = "<span class='badge warn'>remaining</span>";
            Object reason = warning.get("reason");
            String text = reason == null ? "" : reasons.getOrDefault(reason.toString(), reason.toString());
            outcome = escape(text.isEmpty() ? "" : Character.toUpperCase(text.charAt(0)) + text.substring(1)) + ".";
        }
        StringBuilder body = new StringBuilder("<p>" + outcome + "</p>");
        body.append("<dl><dt>Resource</dt><dd><code>").append(escape(String.valueOf(warning.get("resource")))).append("</code> (<code>")
                .append(escape(String.valueOf(warning.get("type")))).append("</code>) is never passed to <code>")
                .append(escape(String.valueOf(warning.get("finalizer")))).append("()</code> on some path.</dd><dt>Checker says</dt><dd>")
                .append(escape(warning.get("leak") == null ? "" : warning.get("leak").toString())).append("</dd></dl>");
        Object firstSeen = warning.get("first_seen");
        if (firstSeen != null && !"initial".equals(firstSeen)) {
            body.append("<p class='muted'>Reported only after an earlier repair step (for example, making a wrapper class closeable "
                    + "exposes the leaks in its callers).</p>");
        }
        Object patch = warning.get("fix_patch");
        if (patch instanceof String text && Files.isRegularFile(Path.of(text))) {
            body.append(diff(read(Path.of(text))));
        }
        String search = (location + " " + warning.get("resource") + " " + warning.get("type")).toLowerCase(java.util.Locale.ROOT);
        return "<details class='leak' data-status='" + status + "' data-search='" + escape(search) + "'><summary>" + badge + "<code>"
                + escape(location) + "</code><span class='res'>" + escape(String.valueOf(warning.get("resource"))) + "</span></summary>"
                + "<div class='body'>" + body + "</div></details>";
    }

    /** The patch split into (file name, diff) pairs. */
    static List<String[]> splitPatch(String text) {
        List<String[]> files = new ArrayList<>();
        List<StringBuilder> bodies = new ArrayList<>();
        for (String line : text.lines().toList()) {
            if (line.startsWith("--- ")) {
                files.add(new String[] {"", ""});
                bodies.add(new StringBuilder(line));
            } else if (line.startsWith("+++ ") && !files.isEmpty() && files.get(files.size() - 1)[0].isEmpty()) {
                String name = line.substring(4);
                int tab = name.indexOf('\t');
                files.get(files.size() - 1)[0] = (tab < 0 ? name : name.substring(0, tab)).strip();
                bodies.get(bodies.size() - 1).append('\n').append(line);
            } else if (!files.isEmpty()) {
                bodies.get(bodies.size() - 1).append('\n').append(line);
            }
        }
        for (int i = 0; i < files.size(); i++) {
            files.get(i)[1] = bodies.get(i).toString();
        }
        return files;
    }

    private static String diff(String text) {
        StringBuilder rows = new StringBuilder();
        for (String line : text.lines().toList()) {
            if (line.startsWith("+++") || line.startsWith("---")) {
                continue;
            }
            String tone = line.startsWith("+") ? "add" : line.startsWith("-") ? "del" : line.startsWith("@@") ? "hunk" : "";
            String escaped = escape(line);
            rows.append("<span class='").append(tone).append("'>").append(escaped.isEmpty() ? " " : escaped).append("</span>");
        }
        // Each line is a block element, so no newline characters between them inside <pre>.
        return "<pre class='diff'>" + rows + "</pre>";
    }

    /** Like Python's html.escape: &, <, >, " and '. */
    static String escape(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#x27;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static final String HEAD = """
            <!DOCTYPE html>
            <html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>{title}</title>
            <style>
            :root { --bg:#ffffff; --fg:#1f2328; --muted:#59636e; --border:#d1d9e0; --panel:#f6f8fa;
              --good:#1a7f37; --warn:#9a6700; --add-bg:#dafbe1; --del-bg:#ffebe9; --hunk:#0969da; }
            @media (prefers-color-scheme: dark) { :root { --bg:#0d1117; --fg:#e6edf3; --muted:#9198a1; --border:#3d444d;
              --panel:#151b23; --good:#3fb950; --warn:#d29922; --add-bg:#12261e; --del-bg:#2d1214; --hunk:#4493f8; } }
            body { margin:0; background:var(--bg); color:var(--fg);
              font:15px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Helvetica,Arial,sans-serif; }
            main { max-width:1100px; margin:0 auto; padding:24px 16px 64px; }
            h1 { font-size:24px; margin:0 0 4px; } h2 { font-size:18px; margin:32px 0 12px; }
            .muted { color:var(--muted); }
            code, pre { font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace; font-size:13px; }
            .cards { display:grid; grid-template-columns:repeat(auto-fit,minmax(150px,1fr)); gap:12px; margin-top:20px; }
            .card { border:1px solid var(--border); border-radius:8px; padding:12px 16px; background:var(--panel); }
            .card .value { font-size:28px; font-weight:600; }
            .card.good .value, .badge.good { color:var(--good); } .card.warn .value, .badge.warn { color:var(--warn); }
            .cmd { background:var(--panel); border:1px solid var(--border); border-radius:6px; padding:10px 12px;
              overflow-x:auto; }
            table.reasons td { padding:3px 12px 3px 0; vertical-align:top; } td.num { text-align:right; font-weight:600; }
            .filters { display:flex; flex-wrap:wrap; gap:8px; margin-bottom:12px; }
            .filters button { border:1px solid var(--border); background:var(--panel); color:var(--fg); border-radius:6px;
              padding:4px 12px; cursor:pointer; font:inherit; }
            .filters button.on { border-color:var(--hunk); color:var(--hunk); }
            .filters input { flex:1; min-width:180px; border:1px solid var(--border); border-radius:6px; padding:4px 8px;
              background:var(--bg); color:var(--fg); font:inherit; }
            details { border:1px solid var(--border); border-radius:6px; margin:6px 0; background:var(--bg); }
            details > summary { cursor:pointer; padding:6px 10px; display:flex; gap:10px; align-items:baseline;
              flex-wrap:wrap; }
            details .body { padding:0 12px 8px; }
            .badge { font-size:12px; font-weight:600; text-transform:uppercase; min-width:76px; }
            .res { color:var(--muted); }
            summary > *, dd, .muted { min-width:0; overflow-wrap:anywhere; }
            dl { display:grid; grid-template-columns:max-content 1fr; gap:4px 12px; } dt { color:var(--muted); }
            dd { margin:0; overflow-wrap:anywhere; }
            pre.diff { background:var(--panel); border:1px solid var(--border); border-radius:6px; padding:8px 0;
              overflow-x:auto; }
            pre.diff span { display:block; padding:0 12px; white-space:pre; }
            pre.diff .add { background:var(--add-bg); } pre.diff .del { background:var(--del-bg); }
            pre.diff .hunk { color:var(--hunk); }
            </style></head><body><main>""";

    private static final String SCRIPT = """
            <script>
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
            </script>""";
}
