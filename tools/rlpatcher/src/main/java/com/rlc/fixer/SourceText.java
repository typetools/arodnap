package com.rlc.fixer;

import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Line-based edits on the original source, used to render a fix as a small textual
 * change instead of re-printing moved AST nodes.
 * <p>
 * JavaParser's lexical-preserving printer drops comments and blank lines of statements
 * that move under a new {@code try}, and indents only their first line. Rendering the
 * same decisions as text keeps every original line and just indents it one level.
 * <p>
 * An edit replaces whole lines, so it is only recorded for statements that sit on their
 * own lines. When that does not hold, {@link #giveUp()} is called and the caller falls
 * back to the AST printer.
 */
final class SourceText {

    private final List<String> lines;
    private final String lineSeparator;
    private final boolean trailingNewline;
    private final List<Edit> edits = new ArrayList<>();
    private boolean usable = true;

    private static final class Edit {
        final int firstLine; // 1-based, inclusive
        final int lastLine; // inclusive; firstLine - 1 for an insertion before firstLine
        final List<String> replacement;

        Edit(int firstLine, int lastLine, List<String> replacement) {
            this.firstLine = firstLine;
            this.lastLine = lastLine;
            this.replacement = replacement;
        }
    }

    SourceText(String content) {
        this.lineSeparator = content.contains("\r\n") ? "\r\n" : "\n";
        this.trailingNewline = content.endsWith("\n");
        String body = trailingNewline ? content.substring(0, content.length() - lineSeparator.length()) : content;
        this.lines = new ArrayList<>(List.of(body.split("\r?\n", -1)));
    }

    String line(int number) {
        return lines.get(number - 1);
    }

    static String indentOf(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t'))
            i++;
        return line.substring(0, i);
    }

    String indentOfLine(int number) {
        return indentOf(line(number));
    }

    /** True when only whitespace precedes the node on its first line. */
    boolean startsLine(Node node) {
        Optional<Range> range = node.getRange();
        return range.isPresent() && line(range.get().begin.line).substring(0, range.get().begin.column - 1).isBlank();
    }

    /** True when only whitespace or a line comment follows the node on its last line. */
    boolean endsLine(Node node) {
        Optional<Range> range = node.getRange();
        if (range.isEmpty())
            return false;
        String rest = line(range.get().end.line).substring(range.get().end.column).trim();
        return rest.isEmpty() || rest.startsWith("//");
    }

    boolean ownsLines(Node node) {
        return startsLine(node) && endsLine(node);
    }

    /** The source text of a range, lines joined with '\n'. */
    String text(Range range) {
        return text(range.begin, range.end);
    }

    String text(Position begin, Position end) {
        if (begin.line == end.line)
            return line(begin.line).substring(begin.column - 1, end.column);
        StringBuilder out = new StringBuilder(line(begin.line).substring(begin.column - 1));
        for (int n = begin.line + 1; n < end.line; n++)
            out.append('\n').append(line(n));
        return out.append('\n').append(line(end.line), 0, end.column).toString();
    }

    /**
     * The indentation step used inside {@code block}: the difference between the
     * indentation of its statements and of the line holding its opening brace.
     */
    String indentUnit(Node block, String statementIndent) {
        String outer = block.getRange().map(r -> indentOfLine(r.begin.line)).orElse("");
        if (statementIndent.startsWith(outer) && statementIndent.length() > outer.length())
            return statementIndent.substring(outer.length());
        return statementIndent.contains("\t") ? "\t" : "    ";
    }

    void replaceLines(int firstLine, int lastLine, List<String> replacement) {
        edits.add(new Edit(firstLine, lastLine, replacement));
    }

    void insertBefore(int line, List<String> inserted) {
        edits.add(new Edit(line, line - 1, inserted));
    }

    void giveUp() {
        usable = false;
    }

    /** The edited source, or empty when an edit could not be expressed as text. */
    Optional<String> result() {
        if (!usable || edits.isEmpty())
            return Optional.empty();
        List<Edit> ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt((Edit e) -> e.firstLine).thenComparingInt(e -> e.lastLine));
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i).firstLine <= ordered.get(i - 1).lastLine)
                return Optional.empty(); // overlapping edits
        }
        List<String> out = new ArrayList<>(lines);
        for (int i = ordered.size() - 1; i >= 0; i--) {
            Edit edit = ordered.get(i);
            List<String> target = out.subList(edit.firstLine - 1, edit.lastLine);
            target.clear();
            target.addAll(edit.replacement);
        }
        String joined = String.join(lineSeparator, out);
        return Optional.of(trailingNewline ? joined + lineSeparator : joined);
    }
}
