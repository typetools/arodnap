package org.arodnap.engine.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.arodnap.engine.diagnostics.CheckerWarning;
import org.arodnap.engine.files.FilePaths;
import org.arodnap.engine.json.Json;

/**
 * RLFixer's input and output formats, and RLPatcher's prompt. The formats are the paper's; file
 * paths are relative to the build's source root. These are read and written nowhere else.
 */
public final class RlFixerFormats {
    /** RLFixer's stdout has this marker before its fixes. */
    public static final String SOURCE_LEVEL_FIXES_MARKER = "SOURCE LEVEL FIXES";
    // RLFixer's hint lines that ask for a source edit; anything else is a note.
    private static final Pattern SOURCE_EDIT = Pattern.compile("^\\+\\+\\+ (?:Add following code|Delete Line number)", Pattern.MULTILINE);
    private static final String NOTHING_TO_BE_DONE = "Nothing to be done";
    private static final Pattern FIX_BLOCK = Pattern.compile(
            "^\\s*(\\d+)\\]\\s*(.+?);\\s*Line\\s+number\\s+(\\d+)\\s*\\n(.*?)(?=^\\s*-{10,}\\s*$|\\z)",
            Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern FIX_ENTRY = Pattern.compile("^\\d+\\] ", Pattern.MULTILINE);

    private RlFixerFormats() {}

    /**
     * RLFixer's warnings file: one {@code relpath,line,None,isOwningOverwrite} line per leak warning,
     * and the leak warnings that are outside the source root (which RLFixer cannot take).
     */
    public record WarningsInput(List<String> lines, List<CheckerWarning> outsideSourceRoot) {
        public boolean isEmpty() {
            return lines.isEmpty();
        }

        /** The file's contents, as the paper's "#"-separated argument with "#" turned into newlines. */
        public String fileContents() {
            return lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
        }
    }

    public static WarningsInput warningsInput(List<CheckerWarning> warnings, Path sourceRoot) {
        List<String> lines = new ArrayList<>();
        List<CheckerWarning> skipped = new ArrayList<>();
        for (CheckerWarning warning : warnings) {
            if (!warning.isLeak()) {
                continue;
            }
            var relative = FilePaths.relativeTo(Path.of(warning.file()), sourceRoot);
            if (relative.isEmpty()) {
                skipped.add(warning);
                continue;
            }
            lines.add(relative.get() + "," + warning.line() + ",None," + (warning.isOwningFieldOverwrite() ? "True" : "False"));
        }
        return new WarningsInput(lines, skipped);
    }

    /** How many fixes RLFixer's report lists. */
    public static int countFixes(String fixesReport) {
        Matcher matcher = FIX_ENTRY.matcher(fixesReport);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * One fix RLFixer suggests.
     *
     * @param file the absolute path RLFixer printed
     * @param relpath the path relative to the source root (the file name if outside it)
     * @param line the leak's line number
     * @param suggestion RLFixer's hint text
     */
    public record FixSuggestion(String file, String relpath, int line, String suggestion) {
        /** True when RLFixer says the leak needs no edit (e.g. a returned resource with no callers). */
        public boolean nothingToDo() {
            return suggestion.contains(NOTHING_TO_BE_DONE) && !SOURCE_EDIT.matcher(suggestion).find();
        }

        public Key key() {
            return new Key(relpath, line);
        }
    }

    /** A warning's location as RLFixer names it: its path relative to the source root and its line. */
    public record Key(String relpath, int line) {}

    public static List<FixSuggestion> parseFixes(String fixesReport, Path sourceRoot) {
        List<FixSuggestion> suggestions = new ArrayList<>();
        Matcher matcher = FIX_BLOCK.matcher(fixesReport);
        while (matcher.find()) {
            String file = matcher.group(2).strip();
            String relpath = FilePaths.relativeTo(Path.of(file), sourceRoot).orElse(Path.of(file).getFileName().toString());
            suggestions.add(new FixSuggestion(file, relpath, Integer.parseInt(matcher.group(3)), matcher.group(4).strip()));
        }
        return suggestions;
    }

    /** What RLFixer's debug table says about a warning. */
    public enum DebugStatus { UNMATCHED, DUPLICATE, UNFIXABLE, FIXABLE }

    /**
     * RLFixer's debug table: the status of each of a warning's rows, in table order. RLFixer can list
     * a warning more than once, for example first as fixable and then as a duplicate of itself.
     */
    public record DebugTable(Map<Key, List<DebugStatus>> rows) {
        public DebugTable {
            rows = Map.copyOf(rows);
        }

        /** Warnings with at least one fixable row: RLFixer materializes a fix for them. */
        public Set<Key> fixable() {
            Set<Key> fixable = new HashSet<>();
            rows.forEach((key, statuses) -> {
                if (statuses.contains(DebugStatus.FIXABLE)) {
                    fixable.add(key);
                }
            });
            return fixable;
        }

        /** What the warning's last row says; the report's reason when RLFixer's fix is not used. */
        public Optional<DebugStatus> lastStatus(Key key) {
            List<DebugStatus> statuses = rows.getOrDefault(key, List.of());
            return statuses.isEmpty() ? Optional.empty() : Optional.of(statuses.get(statuses.size() - 1));
        }
    }

    /**
     * RLFixer's debug table ({@code ^}-separated, with a header row). Empty when the table is
     * missing or unreadable.
     */
    public static DebugTable parseDebugTable(String debugTable) {
        List<String> lines = debugTable.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        Map<Key, List<DebugStatus>> rows = new LinkedHashMap<>();
        if (lines.isEmpty() || !lines.get(0).contains("^")) {
            return new DebugTable(rows);
        }
        Map<String, Integer> columns = new HashMap<>();
        String[] header = lines.get(0).split("\\^", -1);
        for (int i = 0; i < header.length; i++) {
            columns.put(header[i], i);
        }
        for (String name : List.of("Source File", "Line Number", "Matched Method", "Duplicate", "Unfixable")) {
            if (!columns.containsKey(name)) {
                return new DebugTable(rows);
            }
        }
        for (String row : lines.subList(1, lines.size())) {
            String[] parts = row.split("\\^", -1);
            if (parts.length < columns.size()) {
                continue;
            }
            int line;
            try {
                line = Integer.parseInt(parts[columns.get("Line Number")].strip());
            } catch (NumberFormatException e) {
                continue;
            }
            Key key = new Key(parts[columns.get("Source File")].strip().replace('\\', '/'), line);
            DebugStatus status;
            if (parts[columns.get("Matched Method")].strip().equalsIgnoreCase("UNMATCHED")) {
                status = DebugStatus.UNMATCHED;
            } else if (!parts[columns.get("Duplicate")].strip().equalsIgnoreCase("false")) {
                status = DebugStatus.DUPLICATE;
            } else if (!parts[columns.get("Unfixable")].strip().equalsIgnoreCase("false")) {
                status = DebugStatus.UNFIXABLE;
            } else {
                status = DebugStatus.FIXABLE;
            }
            rows.computeIfAbsent(key, ignored -> new ArrayList<>()).add(status);
        }
        return new DebugTable(rows);
    }

    /**
     * The suggestions to materialize: those RLFixer's debug table marks fixable, or all of them when
     * the table is missing.
     */
    public static List<FixSuggestion> selectFixable(List<FixSuggestion> suggestions, DebugTable debugTable) {
        Set<Key> fixable = debugTable.fixable();
        if (fixable.isEmpty()) {
            return List.copyOf(suggestions);
        }
        return suggestions.stream().filter(suggestion -> fixable.contains(suggestion.key())).toList();
    }

    /** A suggestion and the leak warning it fixes. */
    public record Match(FixSuggestion fix, CheckerWarning warning) {}

    /**
     * Pairs each suggestion with the leak warning at the same absolute path and line. Other warnings
     * (such as an {@code assignment} error) can be on the same line; RLFixer only saw leaks.
     */
    public static List<Match> matchFixesToWarnings(List<FixSuggestion> suggestions, List<CheckerWarning> warnings) {
        Map<String, CheckerWarning> byLocation = new HashMap<>();
        for (CheckerWarning warning : warnings) {
            if (warning.isLeak()) {
                byLocation.put(warning.file() + ":" + warning.line(), warning);
            }
        }
        List<Match> matches = new ArrayList<>();
        for (FixSuggestion suggestion : suggestions) {
            CheckerWarning warning = byLocation.get(suggestion.file() + ":" + suggestion.line());
            if (warning != null) {
                matches.add(new Match(suggestion, warning));
            }
        }
        return matches;
    }

    /** RLPatcher's input: the warning and RLFixer's hint, as JSON. */
    public static String rlpatcherPrompt(CheckerWarning warning, FixSuggestion suggestion) {
        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("CF Leaks", List.of(warning.message()));
        prompt.put("RLFixer hint", List.of(suggestion.suggestion()));
        return Json.write(prompt, false);
    }
}
