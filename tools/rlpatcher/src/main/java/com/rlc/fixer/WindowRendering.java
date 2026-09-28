package com.rlc.fixer;

import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders TryWrapAndFinallyTransformer's decisions as text edits (see {@link SourceText}):
 * the wrapped statements keep their comments, blank lines and formatting and are indented
 * one level.
 */
final class WindowRendering {

    private WindowRendering() {
    }

    /** The lines of the wrapped statements, with edits applied; null marks a removed line. */
    static final class Region {
        final SourceText text;
        final int first;
        final int last;
        final String indent;
        final String unit;
        private final String[] lines;
        private final List<List<String>> insertedBefore = new ArrayList<>();
        private boolean ok = true;

        Region(SourceText text, BlockStmt container, List<Statement> slice) {
            this.text = text;
            Statement head = slice.get(0);
            Statement tail = slice.get(slice.size() - 1);
            this.first = head.getRange().map(r -> r.begin.line).orElse(1);
            this.last = tail.getRange().map(r -> r.end.line).orElse(0);
            this.indent = text.indentOfLine(first);
            this.unit = text.indentUnit(container, indent);
            this.lines = new String[Math.max(0, last - first + 1)];
            for (int n = first; n <= last; n++) {
                lines[n - first] = text.line(n);
                insertedBefore.add(new ArrayList<>());
            }
            ok = text.startsLine(head) && text.endsLine(tail);
        }

        boolean contains(Node node) {
            return node.getRange().map(r -> first <= r.begin.line && r.end.line <= last).orElse(false);
        }

        /** Removes the lines of a statement that sits on its own lines. */
        void remove(Node node) {
            if (!contains(node) || !text.ownsLines(node) || hasTrailingComment(node)) {
                ok = false;
                return;
            }
            Range r = node.getRange().get();
            for (int n = r.begin.line; n <= r.end.line; n++)
                lines[n - first] = null;
        }

        /** Replaces the source of a range inside the region. */
        void replace(Range r, String replacement) {
            if (r.begin.line < first || r.end.line > last || lines[r.begin.line - first] == null
                    || lines[r.end.line - first] == null) {
                ok = false;
                return;
            }
            String before = lines[r.begin.line - first].substring(0, r.begin.column - 1);
            String after = lines[r.end.line - first].substring(r.end.column);
            lines[r.begin.line - first] = before + replacement + after;
            for (int n = r.begin.line + 1; n <= r.end.line; n++)
                lines[n - first] = null;
        }

        /** Inserts a line before {@code line}, at that line's indentation. */
        void insertBefore(int line, String code) {
            insertedBefore.get(line - first).add(SourceText.indentOf(text.line(line)) + code);
        }

        boolean ok() {
            return ok;
        }

        /** The region's lines, indented one more level. */
        List<String> body() {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                for (String inserted : insertedBefore.get(i))
                    out.add(unit + inserted);
                if (lines[i] != null)
                    out.add(lines[i].isBlank() ? lines[i] : unit + lines[i]);
            }
            return out;
        }

        private boolean hasTrailingComment(Node node) {
            Range r = node.getRange().get();
            return !text.line(r.end.line).substring(r.end.column).isBlank();
        }
    }

    /**
     * Wraps the slice in try-with-resources. {@code headerSource} is the resource
     * declaration (it may span lines); {@code declaration}, if not null, is the slice
     * statement it came from and is removed; {@code allocation}, if not null, is replaced by
     * {@code variable}.
     */
    static void renderTryWithResources(SourceText text, BlockStmt container, List<Statement> slice,
            String headerSource, Statement declaration, Expression allocation, String variable,
            List<Statement> toDelete) {
        Region region = new Region(text, container, slice);
        if (declaration != null)
            region.remove(declaration);
        if (allocation != null)
            allocation.getRange().ifPresentOrElse(r -> region.replace(r, variable), text::giveUp);
        if (!deleteStatements(text, region, toDelete) || !region.ok()) {
            text.giveUp();
            return;
        }
        List<String> out = new ArrayList<>();
        String[] header = headerSource.split("\n", -1);
        for (int i = 0; i < header.length; i++) {
            String line = i == 0 ? region.indent + "try (" + header[0] : header[i];
            out.add(i == header.length - 1 ? line + ") {" : line);
        }
        out.addAll(region.body());
        out.add(region.indent + "}");
        text.replaceLines(region.first, region.last, out);
    }

    /**
     * Gives an existing {@code try} the resource {@code headerSource}. {@code declaration}, if
     * not null, is the body statement the header came from and is removed; {@code allocation},
     * if not null, is replaced by {@code variable}.
     */
    static void renderResourceAdded(SourceText text, TryStmt target, String headerSource, Statement declaration,
            Expression allocation, String variable) {
        Range tryRange = target.getRange().orElse(null);
        Range body = target.getTryBlock().getRange().orElse(null);
        if (tryRange == null || body == null || !target.getResources().isEmpty() || headerSource.contains("\n")
                || tryRange.begin.line != body.begin.line) {
            text.giveUp();
            return;
        }
        String line = text.line(tryRange.begin.line);
        text.replaceLines(tryRange.begin.line, tryRange.begin.line, List.of(line.substring(0, tryRange.begin.column - 1)
                + "try (" + headerSource + ") " + line.substring(body.begin.column - 1)));
        if (declaration != null) {
            Range decl = declaration.getRange().orElse(null);
            if (decl == null || decl.begin.line == tryRange.begin.line || !text.ownsLines(declaration)
                    || !text.line(decl.end.line).substring(decl.end.column).isBlank()) {
                text.giveUp();
                return;
            }
            text.replaceLines(decl.begin.line, decl.end.line, List.of());
        }
        if (allocation != null) {
            Range alloc = allocation.getRange().orElse(null);
            if (alloc == null || alloc.begin.line == tryRange.begin.line) {
                text.giveUp();
                return;
            }
            String first = text.line(alloc.begin.line);
            String last = text.line(alloc.end.line);
            text.replaceLines(alloc.begin.line, alloc.end.line, List.of(
                    first.substring(0, alloc.begin.column - 1) + variable + last.substring(alloc.end.column)));
        }
    }

    /** Inserts the resource {@code header} before {@code resource}, whose {@code allocation} it takes over. */
    static void renderResourceInserted(SourceText text, Expression resource, Expression allocation, String header,
            String variable) {
        Range res = resource.getRange().orElse(null);
        Range alloc = allocation.getRange().orElse(null);
        if (res == null || alloc == null || res.begin.line != alloc.begin.line || alloc.begin.line != alloc.end.line) {
            text.giveUp();
            return;
        }
        String line = text.line(res.begin.line);
        String replaced = line.substring(0, alloc.begin.column - 1) + variable + line.substring(alloc.end.column);
        text.replaceLines(res.begin.line, res.begin.line, List.of(replaced.substring(0, res.begin.column - 1)
                + header + "; " + replaced.substring(res.begin.column - 1)));
    }

    /** Edits for the try-finally form, collected by the transformer before rendering. */
    static final class FinallyEdits {
        String nullDeclaration; // placed before the try, e.g. "InputStream in = null;"
        Statement declarationToAssignment; // "Type v = init;" becomes "v = init;"
        Node variableName; // name of that declaration
        Statement declarationToRemove;
        Expression allocation; // replaced by the variable, assigned just before its statement
        Statement allocationStatement;
        Node nameToInitialize; // "Type v;" declared before the try becomes "Type v = null;"
    }

    /**
     * Adds a null-checked close of {@code variable} to an existing try statement: a new
     * finally block, or the end of the existing one.
     */
    static void renderAddedFinally(SourceText text, TryStmt target, String variable, String closeCall,
            String catchType, FinallyEdits edits) {
        Range tryRange = target.getRange().orElse(null);
        if (tryRange == null || !text.startsLine(target)) {
            text.giveUp();
            return;
        }
        String i0 = text.indentOfLine(tryRange.begin.line);
        String unit = target.getTryBlock().getStatements().stream().findFirst()
                .map(s -> text.indentUnit(target, s.getRange().map(r -> text.indentOfLine(r.begin.line)).orElse("")))
                .orElse("    ");
        if (edits.nullDeclaration != null)
            text.insertBefore(tryRange.begin.line, List.of(i0 + edits.nullDeclaration));
        if (edits.declarationToAssignment != null) {
            Range decl = edits.declarationToAssignment.getRange().orElse(null);
            Range name = edits.variableName.getRange().orElse(null);
            if (decl == null || name == null || name.begin.line != decl.begin.line) {
                text.giveUp();
                return;
            }
            String line = text.line(decl.begin.line);
            text.replaceLines(decl.begin.line, decl.begin.line,
                    List.of(line.substring(0, decl.begin.column - 1) + line.substring(name.begin.column - 1)));
        }
        if (edits.declarationToRemove != null) {
            Range decl = edits.declarationToRemove.getRange().orElse(null);
            if (decl == null || !text.ownsLines(edits.declarationToRemove)
                    || !text.line(decl.end.line).substring(decl.end.column).isBlank()) {
                text.giveUp();
                return;
            }
            text.replaceLines(decl.begin.line, decl.end.line, List.of());
        }
        if (edits.nameToInitialize != null) {
            Range name = edits.nameToInitialize.getRange().orElse(null);
            if (name == null) {
                text.giveUp();
                return;
            }
            String line = text.line(name.end.line);
            text.replaceLines(name.end.line, name.end.line,
                    List.of(line.substring(0, name.end.column) + " = null" + line.substring(name.end.column)));
        }
        if (edits.allocation != null) {
            Range alloc = edits.allocation.getRange().orElse(null);
            Range stmt = edits.allocationStatement.getRange().orElse(null);
            if (alloc == null || stmt == null || !text.startsLine(edits.allocationStatement)) {
                text.giveUp();
                return;
            }
            text.insertBefore(stmt.begin.line,
                    List.of(text.indentOfLine(stmt.begin.line) + variable + " = " + edits.allocation + ";"));
            String first = text.line(alloc.begin.line);
            String last = text.line(alloc.end.line);
            text.replaceLines(alloc.begin.line, alloc.end.line, List.of(
                    first.substring(0, alloc.begin.column - 1) + variable + last.substring(alloc.end.column)));
        }

        List<String> close = List.of(
                "if (" + variable + " != null) {",
                unit + "try {",
                unit + unit + variable + "." + closeCall + ";",
                unit + "} catch (" + catchType + " e) {",
                unit + unit + "e.printStackTrace();",
                unit + "}",
                "}");
        if (target.getFinallyBlock().isPresent()) {
            // Before the closing brace of the existing finally block.
            Range fin = target.getFinallyBlock().get().getRange().orElse(null);
            if (fin == null || fin.begin.line == fin.end.line
                    || !text.line(fin.end.line).substring(0, fin.end.column - 1).isBlank()) {
                text.giveUp();
                return;
            }
            String inner = text.indentOfLine(fin.end.line) + unit;
            List<String> added = new ArrayList<>();
            for (String line : close)
                added.add(inner + line);
            text.insertBefore(fin.end.line, added);
        } else {
            // After the try statement's closing brace, which must end its line.
            String line = text.line(tryRange.end.line);
            if (!line.substring(tryRange.end.column).isBlank()) {
                text.giveUp();
                return;
            }
            List<String> replacement = new ArrayList<>();
            replacement.add(line.substring(0, tryRange.end.column) + " finally {");
            for (String closeLine : close)
                replacement.add(i0 + unit + closeLine);
            replacement.add(i0 + "}");
            text.replaceLines(tryRange.end.line, tryRange.end.line, replacement);
        }
    }

    static void renderTryFinally(SourceText text, BlockStmt container, List<Statement> slice, String variable,
            String closeCall, String catchType, FinallyEdits edits, List<Statement> toDelete) {
        Region region = new Region(text, container, slice);
        if (edits.declarationToAssignment != null) {
            Range decl = edits.declarationToAssignment.getRange().orElse(null);
            Range name = edits.variableName.getRange().orElse(null);
            if (decl == null || name == null || name.begin.column < 2 || !text.startsLine(edits.declarationToAssignment)) {
                text.giveUp();
                return;
            }
            // Drop everything before the variable name: modifiers, annotations and the type.
            region.replace(new Range(decl.begin, new Position(name.begin.line, name.begin.column - 1)), "");
        }
        if (edits.declarationToRemove != null)
            region.remove(edits.declarationToRemove);
        if (edits.allocation != null) {
            Range alloc = edits.allocation.getRange().orElse(null);
            Range stmt = edits.allocationStatement.getRange().orElse(null);
            if (alloc == null || stmt == null || !text.startsLine(edits.allocationStatement)) {
                text.giveUp();
                return;
            }
            region.insertBefore(stmt.begin.line, variable + " = " + edits.allocation + ";");
            region.replace(alloc, variable);
        }
        if (!deleteStatements(text, region, toDelete) || !region.ok()) {
            text.giveUp();
            return;
        }
        String i0 = region.indent;
        String i1 = i0 + region.unit;
        String i2 = i1 + region.unit;
        String i3 = i2 + region.unit;
        List<String> out = new ArrayList<>();
        if (edits.nullDeclaration != null)
            out.add(i0 + edits.nullDeclaration);
        out.add(i0 + "try {");
        out.addAll(region.body());
        out.add(i0 + "} finally {");
        out.add(i1 + "if (" + variable + " != null) {");
        out.add(i2 + "try {");
        out.add(i3 + variable + "." + closeCall + ";");
        out.add(i2 + "} catch (" + catchType + " e) {");
        out.add(i3 + "e.printStackTrace();");
        out.add(i2 + "}");
        out.add(i1 + "}");
        out.add(i0 + "}");
        text.replaceLines(region.first, region.last, out);
    }

    private static boolean deleteStatements(SourceText text, Region region, List<Statement> toDelete) {
        for (Statement statement : toDelete) {
            if (region.contains(statement)) {
                region.remove(statement);
            } else if (text.ownsLines(statement)) {
                Range r = statement.getRange().get();
                text.replaceLines(r.begin.line, r.end.line, List.of());
            } else {
                return false;
            }
        }
        return true;
    }
}
