package org.arodnap.engine.patch;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a unified diff. Hunks end when their header's line counts are used up, so a context line
 * that happens to start with {@code "--- "} is never mistaken for a file header.
 */
public final class UnifiedDiffParser {
    static final String NO_NEWLINE = "\\ No newline at end of file";
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@");

    private UnifiedDiffParser() {}

    /** Thrown for text that is not a well-formed unified diff. */
    public static final class MalformedPatchException extends Exception {
        private static final long serialVersionUID = 1L;

        MalformedPatchException(String message) {
            super(message);
        }
    }

    public static List<FilePatch> parse(String text) throws MalformedPatchException {
        List<FilePatch> files = new ArrayList<>();
        List<String> lines = Lines.split(text);
        FileBuilder file = null;
        HunkBuilder open = null; // the hunk still being read
        HunkBuilder last = null; // the hunk a "\ No newline" marker refers to
        for (int index = 0; index < lines.size(); index++) {
            String raw = lines.get(index);
            String bare = stripLineEnding(raw);
            if (open == null && bare.startsWith("--- ") && index + 1 < lines.size() && lines.get(index + 1).startsWith("+++ ")) {
                if (file != null) {
                    files.add(file.build());
                }
                file = new FileBuilder(headerPath(bare), headerPath(stripLineEnding(lines.get(++index))));
                last = null;
                continue;
            }
            if (bare.equals(NO_NEWLINE)) {
                if (last != null) {
                    last.dropFinalNewline();
                }
                continue;
            }
            Matcher header = HUNK_HEADER.matcher(bare);
            if (open == null && header.find()) {
                if (file == null) {
                    throw new MalformedPatchException("hunk before any file header: " + bare);
                }
                open = last = new HunkBuilder(Integer.parseInt(header.group(1)), count(header.group(2)), count(header.group(4)));
                file.hunks.add(open);
            } else if (open != null) {
                open.add(raw);
            }
            if (open != null && open.complete()) {
                open = null;
            }
        }
        if (open != null) {
            throw new MalformedPatchException("patch ends in the middle of a hunk");
        }
        if (file == null) {
            throw new MalformedPatchException("no file headers (---/+++) found");
        }
        files.add(file.build());
        return files;
    }

    private static int count(String group) {
        return group == null ? 1 : Integer.parseInt(group);
    }

    private static String headerPath(String line) {
        String path = line.substring(4);
        int tab = path.indexOf('\t');
        return (tab >= 0 ? path.substring(0, tab) : path).strip();
    }

    private static String stripLineEnding(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == '\n' || line.charAt(end - 1) == '\r')) {
            end--;
        }
        return line.substring(0, end);
    }

    private static final class FileBuilder {
        private final String oldPath;
        private final String newPath;
        private final List<HunkBuilder> hunks = new ArrayList<>();

        FileBuilder(String oldPath, String newPath) {
            this.oldPath = oldPath;
            this.newPath = newPath;
        }

        FilePatch build() {
            return new FilePatch(oldPath, newPath, hunks.stream().map(HunkBuilder::build).toList());
        }
    }

    private static final class HunkBuilder {
        private final int oldStart;
        private final int oldCount;
        private int oldLeft;
        private int newLeft;
        private final List<FilePatch.Line> lines = new ArrayList<>();

        HunkBuilder(int oldStart, int oldCount, int newCount) {
            this.oldStart = oldStart;
            this.oldCount = oldCount;
            this.oldLeft = oldCount;
            this.newLeft = newCount;
        }

        void add(String raw) throws MalformedPatchException {
            char first = raw.isEmpty() ? ' ' : raw.charAt(0);
            boolean tagged = first == ' ' || first == '-' || first == '+';
            // A bare newline (some tools drop the space) is an empty context line.
            FilePatch.Line line = tagged ? new FilePatch.Line(first, raw.substring(1)) : new FilePatch.Line(' ', raw);
            lines.add(line);
            if (line.inOld()) {
                oldLeft--;
            }
            if (line.inNew()) {
                newLeft--;
            }
            if (oldLeft < 0 || newLeft < 0) {
                throw new MalformedPatchException("hunk is longer than its header says");
            }
        }

        boolean complete() {
            return oldLeft == 0 && newLeft == 0;
        }

        /** Only the "\n" the diff added; a "\r" before it belongs to the line. */
        void dropFinalNewline() {
            if (lines.isEmpty()) {
                return;
            }
            FilePatch.Line previous = lines.get(lines.size() - 1);
            String text = previous.text();
            if (text.endsWith("\n")) {
                lines.set(lines.size() - 1, new FilePatch.Line(previous.tag(), text.substring(0, text.length() - 1)));
            }
        }

        FilePatch.Hunk build() {
            return new FilePatch.Hunk(oldStart, oldCount, lines);
        }
    }
}
