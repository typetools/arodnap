package org.arodnap.engine.report;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** The short summary {@code arodnap repair} prints when it finishes. */
public final class Summary {
    private static final Pattern SHELL_UNSAFE = Pattern.compile("[^\\w@%+=:,./-]");

    private Summary() {}

    @SuppressWarnings("unchecked")
    public static String format(Map<String, Object> leaks, Path repoRoot, Optional<Path> patch, Path patchDirectory, int changedFiles,
            Optional<Path> htmlReport, String applyCommand) {
        Map<String, Object> summary = (Map<String, Object>) leaks.get("summary");
        long fixed = number(summary.get("fixed"));
        long remaining = number(summary.get("remaining"));
        long exposedCount = number(summary.get("found_during_repair"));
        String exposed = exposedCount > 0 ? " (" + exposedCount + " exposed by an earlier repair step)" : "";
        List<String> lines = new ArrayList<>();
        lines.add("Resource leaks: " + (fixed + remaining) + " found" + exposed + ", " + fixed + " fixed, " + remaining + " remaining");
        if (remaining > 0) {
            Map<String, Object> byReason = (Map<String, Object>) summary.get("remaining_by_reason");
            Map<String, String> reasons = (Map<String, String>) leaks.get("reasons");
            int width = Long.toString(byReason.values().stream().mapToLong(Summary::number).max().orElse(0)).length();
            lines.add("  Remaining because:");
            byReason.forEach((code, count) -> lines.add("    " + String.format("%" + width + "s", number(count)) + "  "
                    + reasons.getOrDefault(code, code)));
        }
        Map<String, Object> fields = (Map<String, Object>) leaks.getOrDefault("field_changes", Map.of());
        List<Map<String, Object>> changes = (List<Map<String, Object>>) fields.getOrDefault("changes", List.of());
        if (!changes.isEmpty()) {
            long finals = changes.stream().filter(change -> "final".equals(change.get("change"))).count();
            lines.add("Fields: " + finals + " resource field(s) made final, " + (changes.size() - finals) + " turned into local variables");
        }
        long others = number(summary.get("other_checker_warnings")) + number(summary.get("javac_warnings"));
        if (others > 0) {
            lines.add("  Also reported: " + others + " other warning(s) that are not resource leaks (see the report)");
        }
        if (patch.isPresent() && changedFiles > 0) {
            lines.add("Patch:  " + patch.get() + " (" + changedFiles + " file(s))");
            lines.add("Apply:  " + applyCommand);
        } else {
            lines.add("No changes to apply.");
        }
        htmlReport.ifPresent(path -> lines.add("Report: " + path));
        return String.join("\n", lines);
    }

    /** The CLI's command for applying the bundle to the project. */
    public static String cliApplyCommand(Path patchDirectory, Path repoRoot) {
        return "arodnap apply --patch-dir " + shellQuote(patchDirectory.toString()) + " " + shellQuote(repoRoot.toString());
    }

    /** Quotes a word for a POSIX shell, like Python's {@code shlex.quote}. */
    public static String shellQuote(String word) {
        if (word.isEmpty()) {
            return "''";
        }
        if (!SHELL_UNSAFE.matcher(word).find()) {
            return word;
        }
        return "'" + word.replace("'", "'\"'\"'") + "'";
    }

    static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }
}
