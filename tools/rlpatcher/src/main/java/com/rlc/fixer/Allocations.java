package com.rlc.fixer;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;

import java.util.List;

/**
 * Finds the leaked allocation the Checker Framework reported.
 * <p>
 * With {@code -Adetailedmsgtext} the warning carries the expression's source offsets,
 * which identify it exactly. The expression text in the warning is only a fallback: the
 * Checker Framework shortens long expressions (e.g. {@code "PomReader.class.get..."}) and
 * prints multi-line ones on one line.
 */
final class Allocations {

    private Allocations() {
    }

    /** The expressions under {@code scope} that are the reported allocation. */
    static List<Expression> find(Node scope, PromptInfo info) {
        if (info.allocationBegin != null && info.allocationEnd != null) {
            List<Expression> byPosition = scope.findAll(Expression.class, e -> e.getRange()
                    .map(r -> r.begin.equals(info.allocationBegin) && r.end.equals(info.allocationEnd))
                    .orElse(false));
            if (!byPosition.isEmpty()) {
                // Nested wrappers such as parentheses can share a range; the outermost comes first.
                return byPosition.subList(0, 1);
            }
        }
        if (info.allocationExprText == null) {
            return List.of();
        }
        String wanted = info.allocationExprText.replace(" ", "");
        return scope.findAll(Expression.class, e -> e.toString().replace(" ", "").equals(wanted));
    }

    /** Converts the Checker Framework's [start, end) character offsets into JavaParser positions. */
    static void setPositions(PromptInfo info, String source, int startOffset, int endOffset) {
        if (startOffset < 0 || endOffset <= startOffset || endOffset > source.length()) {
            return;
        }
        info.allocationBegin = position(source, startOffset);
        info.allocationEnd = position(source, endOffset - 1);
    }

    private static Position position(String source, int offset) {
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new Position(line, offset - lineStart + 1);
    }
}
