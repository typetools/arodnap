package org.arodnap.fields;

import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.fixes.SuggestedFix;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.Tree;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Source positions, indentation and options shared by the field transformations. */
final class Edits {

    /** Types the Checker Framework treats as resources without being {@code AutoCloseable}. */
    static final String EXTRA_RESOURCE_TYPES_FLAG = "ArodnapFields:ExtraResourceTypes";
    /** "true" to transform every eligible field, as in the paper, instead of resource fields only. */
    static final String ALL_FIELDS_FLAG = "ArodnapFields:AllFields";
    /** A file with one identifier per line: names used in string literals (possible reflection). */
    static final String REFLECTED_NAMES_FLAG = "ArodnapFields:ReflectedNamesFile";

    private Edits() {
    }

    static List<String> extraResourceTypes(ErrorProneFlags flags) {
        return flags.getListOrEmpty(EXTRA_RESOURCE_TYPES_FLAG);
    }

    static boolean allFields(ErrorProneFlags flags) {
        return flags.getBoolean(ALL_FIELDS_FLAG).orElse(false);
    }

    static Set<String> reflectedNames(ErrorProneFlags flags) {
        return flags.get(REFLECTED_NAMES_FLAG).map(path -> {
            try {
                return new HashSet<>(Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).orElseGet(HashSet::new);
    }

    /** The whitespace that starts the line containing {@code position}. */
    static String indentAt(VisitorState state, int position) {
        CharSequence source = state.getSourceCode();
        int lineStart = position;
        while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        int end = lineStart;
        while (end < source.length() && (source.charAt(end) == ' ' || source.charAt(end) == '\t')) {
            end++;
        }
        return source.subSequence(lineStart, end).toString();
    }

    /** One indentation step, taken from the statement nested inside {@code outer}. */
    static String indentUnit(VisitorState state, Tree outer, Tree inner) {
        String outerIndent = indentAt(state, ASTHelpers.getStartPosition(outer));
        String innerIndent = inner == null ? "" : indentAt(state, ASTHelpers.getStartPosition(inner));
        if (innerIndent.startsWith(outerIndent) && innerIndent.length() > outerIndent.length()) {
            return innerIndent.substring(outerIndent.length());
        }
        return outerIndent.contains("\t") ? "\t" : "    ";
    }

    /**
     * Deletes {@code tree}, and the whole line when the tree is alone on its lines, so no blank
     * or whitespace-only line is left behind.
     */
    static void deleteWithLine(SuggestedFix.Builder fix, VisitorState state, Tree tree) {
        CharSequence source = state.getSourceCode();
        int start = ASTHelpers.getStartPosition(tree);
        int end = state.getEndPosition(tree);
        int lineStart = start;
        while (lineStart > 0 && (source.charAt(lineStart - 1) == ' ' || source.charAt(lineStart - 1) == '\t')) {
            lineStart--;
        }
        int lineEnd = end;
        while (lineEnd < source.length() && (source.charAt(lineEnd) == ' ' || source.charAt(lineEnd) == '\t')) {
            lineEnd++;
        }
        boolean aloneOnLines = (lineStart == 0 || source.charAt(lineStart - 1) == '\n')
                && (lineEnd == source.length() || source.charAt(lineEnd) == '\n' || source.charAt(lineEnd) == '\r');
        if (!aloneOnLines) {
            fix.replace(start, end, "");
            return;
        }
        if (lineEnd < source.length() && source.charAt(lineEnd) == '\r') {
            lineEnd++;
        }
        if (lineEnd < source.length() && source.charAt(lineEnd) == '\n') {
            lineEnd++;
        }
        fix.replace(lineStart, lineEnd, "");
    }

    /** A name that does not occur as an identifier anywhere in the file. */
    static String freshName(VisitorState state, String base) {
        String source = state.getSourceCode().toString();
        String name = base;
        for (int i = 2; java.util.regex.Pattern.compile("\\b" + name + "\\b").matcher(source).find(); i++) {
            name = base + i;
        }
        return name;
    }
}
