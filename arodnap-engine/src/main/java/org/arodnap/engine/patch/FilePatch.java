package org.arodnap.engine.patch;

import java.util.List;

/**
 * The changes to one file in a unified diff.
 *
 * @param oldPath the path after {@code ---}, or {@code /dev/null} when the patch creates the file
 * @param newPath the path after {@code +++}, or {@code /dev/null} when the patch deletes the file
 * @param hunks the file's hunks, in order
 */
public record FilePatch(String oldPath, String newPath, List<Hunk> hunks) {
    public static final String DEV_NULL = "/dev/null";

    public FilePatch {
        hunks = List.copyOf(hunks);
    }

    public boolean creates() {
        return oldPath.equals(DEV_NULL);
    }

    public boolean deletes() {
        return newPath.equals(DEV_NULL);
    }

    /**
     * One hunk.
     *
     * @param oldStart the old file's line number in the {@code @@} header
     * @param oldCount how many old lines (context and removed) the hunk covers
     * @param lines the hunk's lines
     */
    public record Hunk(int oldStart, int oldCount, List<Line> lines) {
        public Hunk {
            lines = List.copyOf(lines);
        }

        /** 0-based index of the first old line; a hunk that removes nothing inserts after oldStart. */
        int anchor() {
            return oldCount == 0 ? oldStart : Math.max(oldStart - 1, 0);
        }

        /** Number of context lines before the first change and after the last one. */
        int[] contextEdges() {
            int leading = 0;
            while (leading < lines.size() && lines.get(leading).tag() == ' ') {
                leading++;
            }
            int trailing = 0;
            while (trailing < lines.size() - leading && lines.get(lines.size() - 1 - trailing).tag() == ' ') {
                trailing++;
            }
            return new int[] {leading, trailing};
        }
    }

    /**
     * One line of a hunk.
     *
     * @param tag {@code ' '} for context, {@code '-'} for removed, {@code '+'} for added
     * @param text the line with its ending; without one at the end of a file that has none
     */
    public record Line(char tag, String text) {
        boolean inOld() {
            return tag != '+';
        }

        boolean inNew() {
            return tag != '-';
        }
    }
}
