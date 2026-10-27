package org.arodnap.engine.patch;

import java.util.List;

/** Writes unified diffs the way Arodnap's patch bundle has them: exact bytes, 3 lines of context. */
public final class UnifiedDiff {
    private static final int CONTEXT = 3;

    private UnifiedDiff() {}

    /**
     * The diff turning {@code oldText} into {@code newText}, with {@code path} on both header lines.
     * Empty when the texts are equal. A last line without a newline is marked
     * {@code "\ No newline at end of file"}.
     */
    public static String create(String path, String oldText, String newText) {
        List<String> a = Lines.split(oldText);
        List<String> b = Lines.split(newText);
        StringBuilder out = new StringBuilder();
        boolean started = false;
        for (List<SequenceMatcher.Opcode> group : new SequenceMatcher(a, b).groupedOpcodes(CONTEXT)) {
            if (!started) {
                started = true;
                out.append("--- ").append(path).append('\n');
                out.append("+++ ").append(path).append('\n');
            }
            SequenceMatcher.Opcode first = group.get(0);
            SequenceMatcher.Opcode last = group.get(group.size() - 1);
            out.append("@@ -").append(range(first.i1(), last.i2()))
                    .append(" +").append(range(first.j1(), last.j2())).append(" @@\n");
            for (SequenceMatcher.Opcode code : group) {
                if (code.tag().equals("equal")) {
                    lines(out, ' ', a.subList(code.i1(), code.i2()));
                    continue;
                }
                if (!code.tag().equals("insert")) {
                    lines(out, '-', a.subList(code.i1(), code.i2()));
                }
                if (!code.tag().equals("delete")) {
                    lines(out, '+', b.subList(code.j1(), code.j2()));
                }
            }
        }
        return out.toString();
    }

    private static void lines(StringBuilder out, char tag, List<String> lines) {
        for (String line : lines) {
            out.append(tag).append(line);
            if (!line.endsWith("\n")) {
                out.append('\n').append(UnifiedDiffParser.NO_NEWLINE).append('\n');
            }
        }
    }

    /** difflib's _format_range_unified. */
    private static String range(int start, int stop) {
        int beginning = start + 1;
        int length = stop - start;
        if (length == 1) {
            return Integer.toString(beginning);
        }
        if (length == 0) {
            beginning--;
        }
        return beginning + "," + length;
    }
}
