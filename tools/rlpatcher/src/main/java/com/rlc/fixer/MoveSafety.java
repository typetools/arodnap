package com.rlc.fixer;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;

/**
 * Decides whether a resource allocation can be moved out of its statement without
 * changing what the program does.
 * <p>
 * The patch forms move the leaked allocation {@code E} either into a try-with-resources
 * header or into an assignment {@code tmp = E;} placed just before its statement. Both
 * evaluate {@code E} earlier than the original code did, which is only equivalent when
 * <ul>
 * <li>{@code E} is always evaluated when its statement runs (not inside a condition,
 * a short-circuit operand, a lambda or a nested block), and</li>
 * <li>nothing that can have a side effect is evaluated before {@code E} in that
 * statement (Java evaluates operands left to right).</li>
 * </ul>
 * For example, in {@code read(conn.getContentEncoding(), conn.getInputStream())} the
 * input stream cannot be opened before {@code getContentEncoding()} runs.
 */
final class MoveSafety {

    private MoveSafety() {
    }

    /**
     * True when {@code allocation} is the first effect of {@code statement} and always runs.
     * The statement may also be a try-with-resources resource declaration.
     */
    static boolean isFirstEffectOf(Expression allocation, Node statement) {
        Node child = allocation;
        Node parent = allocation.getParentNode().orElse(null);
        while (parent != null) {
            if (!evaluatesChildUnconditionally(parent, child, statement)) {
                return false;
            }
            for (Node sibling : parent.getChildNodes()) {
                if (sibling != child && startsBefore(sibling, child) && hasSideEffects(sibling)) {
                    return false;
                }
            }
            if (parent == statement) {
                return true;
            }
            child = parent;
            parent = parent.getParentNode().orElse(null);
        }
        return false;
    }

    private static boolean evaluatesChildUnconditionally(Node parent, Node child, Node statement) {
        if (parent instanceof Statement) {
            return parent == statement
                    && (parent instanceof ExpressionStmt || parent instanceof ReturnStmt || parent instanceof ThrowStmt);
        }
        if (parent instanceof BinaryExpr) {
            BinaryExpr.Operator operator = ((BinaryExpr) parent).getOperator();
            boolean shortCircuit = operator == BinaryExpr.Operator.AND || operator == BinaryExpr.Operator.OR;
            return !shortCircuit || ((BinaryExpr) parent).getLeft() == child;
        }
        return parent instanceof MethodCallExpr
                || parent instanceof ObjectCreationExpr && isArgumentOrScope((ObjectCreationExpr) parent, child)
                || parent instanceof CastExpr
                || parent instanceof EnclosedExpr
                || parent instanceof FieldAccessExpr
                || parent instanceof ArrayAccessExpr
                || parent instanceof AssignExpr
                || parent instanceof VariableDeclarator
                || parent instanceof VariableDeclarationExpr;
    }

    private static boolean isArgumentOrScope(ObjectCreationExpr creation, Node child) {
        // An anonymous class body is not evaluated when the object is created.
        return creation.getArguments().contains(child) || creation.getScope().map(s -> s == child).orElse(false);
    }

    private static boolean startsBefore(Node a, Node b) {
        Position aBegin = a.getBegin().orElse(null);
        Position bBegin = b.getBegin().orElse(null);
        return aBegin != null && bBegin != null && aBegin.isBefore(bBegin);
    }

    /** Conservative: any call, object creation, assignment or increment counts as a side effect. */
    static boolean hasSideEffects(Node node) {
        if (node instanceof Comment) {
            return false;
        }
        if (isEffect(node)) {
            return true;
        }
        return node.findFirst(Node.class, MoveSafety::isEffect).isPresent();
    }

    private static boolean isEffect(Node node) {
        if (node instanceof MethodCallExpr || node instanceof ObjectCreationExpr || node instanceof AssignExpr) {
            return true;
        }
        if (node instanceof UnaryExpr) {
            UnaryExpr.Operator operator = ((UnaryExpr) node).getOperator();
            return operator == UnaryExpr.Operator.PREFIX_INCREMENT || operator == UnaryExpr.Operator.PREFIX_DECREMENT
                    || operator == UnaryExpr.Operator.POSTFIX_INCREMENT
                    || operator == UnaryExpr.Operator.POSTFIX_DECREMENT;
        }
        return false;
    }
}
