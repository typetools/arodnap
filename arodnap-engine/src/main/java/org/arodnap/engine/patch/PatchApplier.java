package org.arodnap.engine.patch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Applies unified diffs like GNU patch does for the diffs Arodnap produces.
 *
 * <p>Each hunk goes where its old lines (context and removed lines) match, at the nearest position
 * to where the diff says, so earlier edits that shift lines are fine. {@code fuzz} lets up to that
 * many context lines at each end of a hunk go unmatched, like {@code patch -F}.
 * {@code ignoreWhitespace} compares lines with whitespace collapsed. Nothing is written unless
 * every hunk of every file applies.
 *
 * <p>A file keeps its own line endings: lines match ignoring a trailing {@code "\r"}, context lines
 * keep the file's bytes, and inserted lines get the file's ending ({@code "\r\n"} in a
 * Windows-style file) whatever the patch has.
 */
public final class PatchApplier {
    private PatchApplier() {}

    /**
     * How to apply a patch.
     *
     * @param stripLevel leading path components to drop, like {@code patch -p}
     * @param checkOnly only check that the patch applies; write nothing
     * @param fuzz context lines at each hunk end that may go unmatched
     * @param ignoreWhitespace compare lines with runs of whitespace collapsed
     */
    public record Options(int stripLevel, boolean checkOnly, int fuzz, boolean ignoreWhitespace) {
        public static final Options EXACT = new Options(0, false, 0, false);
    }

    /**
     * The result of applying a patch.
     *
     * @param ok whether every hunk applied
     * @param patched root-relative paths of the files written (none when not ok or checkOnly)
     * @param messages one "patching file" line per file, like GNU patch prints
     * @param errors why hunks or files failed
     */
    public record Outcome(boolean ok, List<String> patched, List<String> messages, List<String> errors) {
        public Outcome {
            patched = List.copyOf(patched);
            messages = List.copyOf(messages);
            errors = List.copyOf(errors);
        }
    }

    public static Outcome apply(Path root, String patchText, Options options) throws IOException {
        List<FilePatch> filePatches;
        try {
            filePatches = UnifiedDiffParser.parse(patchText);
        } catch (UnifiedDiffParser.MalformedPatchException e) {
            return new Outcome(false, List.of(), List.of(), List.of("malformed patch: " + e.getMessage()));
        }
        // Result per target; an empty Optional deletes the file.
        Map<Path, Optional<List<String>>> results = new LinkedHashMap<>();
        List<String> messages = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (FilePatch filePatch : filePatches) {
            String relative = strip(filePatch.deletes() ? filePatch.oldPath() : filePatch.newPath(), options.stripLevel());
            Path target = root.resolve(relative);
            messages.add((options.checkOnly() ? "checking" : "patching") + " file " + relative);
            List<String> original;
            if (results.containsKey(target)) {
                original = results.get(target).orElse(List.of());
            } else if (filePatch.creates()) {
                if (Files.exists(target)) {
                    errors.add(relative + ": the patch creates it, but it already exists");
                    continue;
                }
                original = List.of();
            } else if (Files.isRegularFile(target)) {
                original = Lines.split(Lines.read(target));
            } else {
                errors.add("can't find file to patch: " + relative);
                continue;
            }
            List<String> failures = new ArrayList<>();
            List<String> patched = applyHunks(original, filePatch.hunks(), options, failures);
            if (!failures.isEmpty()) {
                failures.forEach(failure -> errors.add(relative + ": " + failure));
                continue;
            }
            results.put(target, filePatch.deletes() ? Optional.empty() : Optional.of(patched));
        }
        if (!errors.isEmpty()) {
            return new Outcome(false, List.of(), messages, errors);
        }
        if (!options.checkOnly()) {
            for (Map.Entry<Path, Optional<List<String>>> entry : results.entrySet()) {
                Path target = entry.getKey();
                if (entry.getValue().isEmpty()) {
                    Files.delete(target);
                } else {
                    Files.createDirectories(target.toAbsolutePath().getParent());
                    Lines.write(target, String.join("", entry.getValue().get()));
                }
            }
        }
        List<String> patchedFiles = results.keySet().stream().map(target -> relativize(root, target)).toList();
        return new Outcome(true, patchedFiles, messages, List.of());
    }

    private static List<String> applyHunks(List<String> lines, List<FilePatch.Hunk> hunks, Options options, List<String> failures) {
        List<String> result = new ArrayList<>(lines);
        String ending = fileLineEnding(lines);
        int delta = 0; // how far the file has moved relative to the diff's line numbers
        int floor = 0; // hunks apply in order and never overlap
        int number = 0;
        for (FilePatch.Hunk hunk : hunks) {
            number++;
            int expected = hunk.anchor() + delta;
            int[] edges = hunk.contextEdges();
            int leading = edges[0];
            int trailing = edges[1];
            Placement placed = null;
            for (int dropped = 0; dropped <= options.fuzz(); dropped++) {
                int lead = Math.min(dropped, leading);
                int trail = Math.min(dropped, trailing);
                List<FilePatch.Line> window = hunk.lines().subList(lead, hunk.lines().size() - trail);
                List<String> old = window.stream().filter(FilePatch.Line::inOld).map(FilePatch.Line::text).toList();
                int position = find(result, old, expected + lead, floor, options.ignoreWhitespace());
                if (position >= 0) {
                    List<String> matched = new ArrayList<>(result.subList(position, position + old.size()));
                    placed = new Placement(position, old.size(), replacement(window, matched, ending), lead);
                    break;
                }
                if (dropped >= Math.max(leading, trailing)) {
                    break;
                }
            }
            if (placed == null) {
                List<String> oldLines = hunk.lines().stream().filter(FilePatch.Line::inOld).map(FilePatch.Line::text).toList();
                List<String> newLines = hunk.lines().stream().filter(FilePatch.Line::inNew).map(FilePatch.Line::text).toList();
                boolean already = !oldLines.equals(newLines) && find(result, newLines, expected, floor, options.ignoreWhitespace()) >= 0;
                failures.add("hunk #" + number + " FAILED at " + hunk.oldStart()
                        + (already ? " (the change seems to be applied already)" : ""));
                continue;
            }
            List<String> region = result.subList(placed.position, placed.position + placed.oldSize);
            region.clear();
            region.addAll(placed.replacement);
            // Lines after this hunk now sit this far from where the diff numbers them.
            delta = placed.position - (hunk.anchor() + placed.lead) + (placed.replacement.size() - placed.oldSize);
            floor = placed.position + placed.replacement.size();
        }
        return result;
    }

    private record Placement(int position, int oldSize, List<String> replacement, int lead) {}

    /**
     * The hunk's new lines: context lines as they are in the file, inserted lines with the file's
     * line ending, or the patch's when the file has none to follow ({@code ending} is null).
     */
    private static List<String> replacement(List<FilePatch.Line> window, List<String> matched, String ending) {
        List<String> result = new ArrayList<>();
        int oldIndex = 0;
        for (FilePatch.Line line : window) {
            switch (line.tag()) {
                case ' ' -> result.add(matched.get(oldIndex++));
                case '-' -> oldIndex++;
                default -> result.add(ending != null && line.text().endsWith("\n")
                        ? stripLineEnding(line.text()) + ending : line.text());
            }
        }
        return result;
    }

    /** The file's line ending, or null for a file without any (empty, or one unterminated line). */
    private static String fileLineEnding(List<String> lines) {
        long ended = lines.stream().filter(line -> line.endsWith("\n")).count();
        if (ended == 0) {
            return null;
        }
        long crlf = lines.stream().filter(line -> line.endsWith("\r\n")).count();
        return crlf > ended - crlf ? "\r\n" : "\n";
    }

    /** Position of the match nearest to {@code expected} at or after {@code floor}, or -1. */
    private static int find(List<String> lines, List<String> wanted, int expected, int floor, boolean ignoreWhitespace) {
        if (wanted.isEmpty()) {
            return Math.max(Math.min(expected, lines.size()), floor);
        }
        int last = lines.size() - wanted.size();
        if (last < floor) {
            return -1;
        }
        List<String> target = wanted.stream().map(line -> normalize(line, ignoreWhitespace)).toList();
        int start = Math.min(Math.max(expected, floor), last);
        for (int distance = 0; distance <= Math.max(start - floor, last - start); distance++) {
            for (int position : new int[] {start - distance, start + distance}) {
                if (position >= floor && position <= last && matchesAt(lines, target, position, ignoreWhitespace)) {
                    return position;
                }
            }
        }
        return -1;
    }

    private static boolean matchesAt(List<String> lines, List<String> target, int position, boolean ignoreWhitespace) {
        for (int i = 0; i < target.size(); i++) {
            if (!normalize(lines.get(position + i), ignoreWhitespace).equals(target.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(String line, boolean ignoreWhitespace) {
        if (ignoreWhitespace) {
            return collapseWhitespace(line);
        }
        boolean ends = line.endsWith("\n") || line.endsWith("\r");
        return stripLineEnding(line) + (ends ? "\n" : "");
    }

    /** Removes every trailing "\r" and "\n", like Python's {@code rstrip("\r\n")}. */
    static String stripLineEnding(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == '\n' || line.charAt(end - 1) == '\r')) {
            end--;
        }
        return line.substring(0, end);
    }

    /**
     * The line's words joined by single spaces, with whitespace as Python's {@code str.split()}
     * sees it in UTF-8 text: ASCII whitespace plus Unicode spaces such as a no-break space. Bytes
     * that are not valid UTF-8 are kept as they are.
     */
    static String collapseWhitespace(String line) {
        byte[] bytes = line.getBytes(StandardCharsets.ISO_8859_1);
        StringBuilder out = new StringBuilder();
        boolean pendingSpace = false;
        int i = 0;
        while (i < bytes.length) {
            int length = utf8SequenceLength(bytes, i);
            int codePoint = length == 1 ? bytes[i] & 0xff : decode(bytes, i, length);
            boolean space = (length > 1 || (bytes[i] & 0xff) < 0x80) && isPythonSpace(codePoint);
            if (space) {
                pendingSpace = out.length() > 0;
            } else {
                if (pendingSpace) {
                    out.append(' ');
                    pendingSpace = false;
                }
                out.append(line, i, i + length);
            }
            i += length;
        }
        return out.toString();
    }

    private static boolean isPythonSpace(int codePoint) {
        return Character.isWhitespace(codePoint)
                || codePoint == 0x85 || codePoint == 0xa0 || codePoint == 0x2007 || codePoint == 0x202f;
    }

    /** Length of the valid UTF-8 sequence at {@code i}; 1 for ASCII and for invalid bytes. */
    private static int utf8SequenceLength(byte[] bytes, int i) {
        int lead = bytes[i] & 0xff;
        int length = lead >= 0xc2 && lead <= 0xdf ? 2 : lead >= 0xe0 && lead <= 0xef ? 3 : lead >= 0xf0 && lead <= 0xf4 ? 4 : 1;
        if (length == 1 || i + length > bytes.length) {
            return 1;
        }
        for (int k = 1; k < length; k++) {
            if ((bytes[i + k] & 0xc0) != 0x80) {
                return 1;
            }
        }
        return length;
    }

    private static int decode(byte[] bytes, int i, int length) {
        return new String(bytes, i, length, StandardCharsets.UTF_8).codePointAt(0);
    }

    private static String strip(String path, int level) {
        if (level == 0) {
            return path;
        }
        String[] parts = path.split("/", -1);
        return String.join("/", Arrays.asList(parts).subList(Math.min(level, parts.length), parts.length));
    }

    private static String relativize(Path root, Path target) {
        try {
            return root.relativize(target).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return target.toString();
        }
    }
}
