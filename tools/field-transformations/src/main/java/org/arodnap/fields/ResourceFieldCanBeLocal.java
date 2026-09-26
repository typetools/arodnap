package org.arodnap.fields;


import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.util.ASTHelpers.getStartPosition;
import static com.google.errorprone.util.ASTHelpers.getSymbol;
import static com.google.errorprone.util.ASTHelpers.shouldKeep;

import com.google.auto.service.AutoService;
import com.google.common.collect.Iterables;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.SetMultimap;
import com.google.errorprone.BugPattern;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.fixes.SuggestedFixes;
import com.google.errorprone.util.ASTHelpers;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.CompilationUnitTreeMatcher;
import com.google.errorprone.fixes.SuggestedFix;
import com.google.errorprone.matchers.Description;
import com.sun.source.tree.*;
import com.sun.source.tree.Tree.Kind;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.lang.model.element.ElementKind;

/**
 * Turns a private resource field into a local variable when every method that uses it assigns it
 * before reading it (Arodnap's "reducing scope" transformation). Based on Error Prone's
 * FieldCanBeLocal, extended to assignments inside {@code try}.
 *
 * <p>Only fields whose type can hold a resource are changed (see {@link ResourceFields}). Fields
 * are left alone when they have an initializer (removing it would drop its side effects), carry
 * annotations (frameworks may inject them), or have a name that appears in a string literal
 * anywhere in the program (reflection could read them). Every change is compile-checked.
 */
@AutoService(BugChecker.class)
@BugPattern(
        name = "ResourceFieldCanBeLocal",
        summary = "Resource field can be a local variable",
        severity = SUGGESTION,
        documentSuppression = false)
public final class ResourceFieldCanBeLocal extends BugChecker implements CompilationUnitTreeMatcher {

    private final List<String> extraResourceTypes;
    private final Set<String> reflectedNames;
    private final boolean allFields;

    /** For {@link java.util.ServiceLoader}, which Error Prone uses to find plugins. */
    public ResourceFieldCanBeLocal() {
        this(ErrorProneFlags.empty());
    }

    @Inject
    public ResourceFieldCanBeLocal(ErrorProneFlags flags) {
        extraResourceTypes = Edits.extraResourceTypes(flags);
        reflectedNames = Edits.reflectedNames(flags);
        allFields = Edits.allFields(flags);
    }

    @Override
    public Description matchCompilationUnit(CompilationUnitTree tree, VisitorState state) {
        ResourceFields resourceFields = ResourceFields.of(state, extraResourceTypes);
        Map<VarSymbol, TreePath> potentialFields = new LinkedHashMap<>();
        SetMultimap<VarSymbol, TreePath> unconditionalAssignments =
                MultimapBuilder.linkedHashKeys().linkedHashSetValues().build();
        SetMultimap<VarSymbol, Tree> uses =
                MultimapBuilder.linkedHashKeys().linkedHashSetValues().build();

        new SuppressibleTreePathScanner<Void, Void>(state) {
            @Override
            public Void visitVariable(VariableTree variableTree, Void unused) {
                VarSymbol symbol = getSymbol(variableTree);
                if (symbol.getKind() == ElementKind.FIELD
                        && symbol.isPrivate()
                        && (allFields || resourceFields.isRelevant(symbol.type))
                        && (variableTree.getInitializer() == null
                                || variableTree.getInitializer().getKind() == Kind.NULL_LITERAL)
                        && variableTree.getModifiers().getAnnotations().isEmpty()
                        && !reflectedNames.contains(symbol.getSimpleName().toString())
                        && !shouldKeep(variableTree)) {
                    potentialFields.put(symbol, getCurrentPath());
                }
                return null;
            }
        }.scan(state.getPath(), null);


        new TreePathScanner<Void, Void>() {
            boolean inMethod = false;

            @Override
            public Void visitClass(ClassTree classTree, Void unused) {
                if (isSuppressed(classTree, state)) {
                    return null;
                }
                inMethod = false;
                return super.visitClass(classTree, null);
            }

            @Override
            public Void visitMethod(MethodTree methodTree, Void unused) {
                if (methodTree.getBody() == null) {
                    return null;
                }
                handleMethodLike(new TreePath(getCurrentPath(), methodTree.getBody()));

                inMethod = true;
                super.visitMethod(methodTree, null);
                inMethod = false;
                return null;
            }

            @Override
            public Void visitLambdaExpression(LambdaExpressionTree lambdaExpressionTree, Void unused) {
                if (lambdaExpressionTree.getBody() == null) {
                    return null;
                }
                handleMethodLike(new TreePath(getCurrentPath(), lambdaExpressionTree.getBody()));
                inMethod = true;
                super.visitLambdaExpression(lambdaExpressionTree, null);
                inMethod = false;
                return null;
            }

            private void handleMethodLike(TreePath treePath) {
                int depth = Iterables.size(getCurrentPath());
                new TreePathScanner<Void, Void>() {
                    Set<VarSymbol> unconditionallyAssigned = new HashSet<>();
                    boolean inTryCatch = false;

                    @Override
                    public Void visitTry(TryTree tryTree, Void unused) {
                        // Resources are read before the block runs, like any other use.
                        scan(tryTree.getResources(), unused);
                        boolean previousInTryCatch = inTryCatch;
                        inTryCatch = true; // Entering try block
                        scan(tryTree.getBlock(), unused);
                        inTryCatch = previousInTryCatch; // Restoring previous state after exiting try block
                        for (Tree catchBlock : tryTree.getCatches()) {
                            scan(catchBlock, unused);
                        }
                        scan(tryTree.getFinallyBlock(), unused);
                        return null;
                    }

                    @Override
                    public Void visitAssignment(AssignmentTree assignmentTree, Void unused) {
                        scan(assignmentTree.getExpression(), null);
                        Symbol symbol = getSymbol(assignmentTree.getVariable());
                        if (!(symbol instanceof VarSymbol)) {
                            return scan(assignmentTree.getVariable(), null);
                        }
                        VarSymbol varSymbol = (VarSymbol) symbol;
                        if (!potentialFields.containsKey(varSymbol)) {
                            return scan(assignmentTree.getVariable(), null);
                        }
                        // An unconditional assignment in a MethodTree is three levels deeper than the
                        // MethodTree itself.
                        if ((Iterables.size(getCurrentPath()) == depth + 3 || inTryCatch) && !isConditional(getCurrentPath())) {
                            unconditionallyAssigned.add(varSymbol);
                            unconditionalAssignments.put(varSymbol, getCurrentPath());
                        }
                        return scan(assignmentTree.getVariable(), null);
                    }

                    private boolean isConditional(TreePath path) {
                        // Check if the path contains a conditional statement (if, while, etc.)
                        for (Tree node : path) {
                            if (node.getKind() == Kind.IF || node.getKind() == Kind.WHILE_LOOP ||
                                    node.getKind() == Kind.FOR_LOOP || node.getKind() == Kind.DO_WHILE_LOOP ||
                                    node.getKind() == Kind.ENHANCED_FOR_LOOP) {
                                return true;
                            }
                        }
                        return false;
                    }

                    @Override
                    public Void visitIdentifier(IdentifierTree identifierTree, Void unused) {
                        handleIdentifier(identifierTree);
                        return super.visitIdentifier(identifierTree, null);
                    }

                    @Override
                    public Void visitMemberSelect(MemberSelectTree memberSelectTree, Void unused) {
                        handleIdentifier(memberSelectTree);
                        return super.visitMemberSelect(memberSelectTree, null);
                    }

                    private void handleIdentifier(Tree tree) {

                        Symbol symbol = getSymbol(tree);
                        if (!(symbol instanceof VarSymbol)) {
                            return;
                        }
                        VarSymbol varSymbol = (VarSymbol) symbol;
                        uses.put(varSymbol, tree);
                        if (!unconditionallyAssigned.contains(varSymbol)) {
                            potentialFields.remove(varSymbol);
                        }
                    }

                    @Override
                    public Void visitNewClass(NewClassTree node, Void unused) {
                        unconditionallyAssigned.clear();
                        return super.visitNewClass(node, null);
                    }

                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
//                        unconditionallyAssigned.clear();
                        return super.visitMethodInvocation(node, null);
                    }

                    @Override
                    public Void visitMethod(MethodTree methodTree, Void unused) {
                        return null;
                    }

                    @Override
                    public Void visitLambdaExpression(
                            LambdaExpressionTree lambdaExpressionTree, Void unused) {
                        return null;
                    }
                }.scan(treePath, null);
            }

            @Override
            public Void visitIdentifier(IdentifierTree identifierTree, Void unused) {
                if (!inMethod) {
                    potentialFields.remove(getSymbol(identifierTree));
                }
                return null;
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree memberSelectTree, Void unused) {
                if (!inMethod) {
                    potentialFields.remove(getSymbol(memberSelectTree));
                }
                return super.visitMemberSelect(memberSelectTree, null);
            }
        }.scan(state.getPath(), null);




        for (Map.Entry<VarSymbol, TreePath> entry : potentialFields.entrySet()) {
            VarSymbol varSymbol = entry.getKey();
            TreePath declarationSite = entry.getValue();

            Collection<TreePath> assignmentLocations = unconditionalAssignments.get(varSymbol);
            if (assignmentLocations.isEmpty()) {
                continue;
            }
            SuggestedFix.Builder fix = SuggestedFix.builder();
            VariableTree variableTree = (VariableTree) declarationSite.getLeaf();
            String type = state.getSourceForNode(variableTree.getType());
            String name = varSymbol.getSimpleName().toString();
            Edits.deleteWithLine(fix, state, variableTree);
            Set<Tree> deletedTrees = new HashSet<>();
            Set<Tree> scopesDeclared = new HashSet<>();
            for (TreePath assignmentSite : assignmentLocations) {
                AssignmentTree assignmentTree = (AssignmentTree) assignmentSite.getLeaf();
                Symbol rhsSymbol = getSymbol(assignmentTree.getExpression());

                // "this.x = x;" from a parameter named like the field: the parameter serves as the
                // local variable, so the assignment goes away.
                if (rhsSymbol != null
                        && assignmentTree.getExpression() instanceof IdentifierTree
                        && rhsSymbol.getSimpleName().contentEquals(name)) {
                    deletedTrees.add(assignmentTree.getVariable());
                    Edits.deleteWithLine(fix, state, assignmentSite.getParentPath().getLeaf());
                    continue;
                }
                TryTree enclosingTry = enclosingTry(assignmentSite);
                if (enclosingTry != null) {
                    // Declared before the try, so code after the try still sees it.
                    if (scopesDeclared.add(enclosingTry)) {
                        String indent = Edits.indentAt(state, ASTHelpers.getStartPosition(enclosingTry));
                        fix.prefixWith(enclosingTry, type + " " + name + " = null;\n" + indent);
                    }
                } else if (scopesDeclared.add(assignmentSite.getParentPath().getParentPath().getLeaf())) {
                    fix.prefixWith(assignmentSite.getLeaf(), type + " ");
                }
            }
            // Strip "this." off any uses of the field.
            for (Tree usage : uses.get(varSymbol)) {
                if (deletedTrees.contains(usage) || usage.getKind() != Kind.MEMBER_SELECT) {
                    continue;
                }
                ExpressionTree selected = ((MemberSelectTree) usage).getExpression();
                if (selected instanceof IdentifierTree && ((IdentifierTree) selected).getName().contentEquals("this")) {
                    fix.replace(getStartPosition(selected), state.getEndPosition(selected) + 1, "");
                }
            }
            SuggestedFix built = fix.build();
            if (SuggestedFixes.compilesWithFix(built, state)) {
                state.reportMatch(buildDescription(variableTree)
                        .setMessage("Resource field `" + name + "` can be a local variable")
                        .addFix(built)
                        .build());
            }
        }
        return Description.NO_MATCH;
    }

    private static TryTree enclosingTry(TreePath path) {
        for (TreePath current = path; current != null; current = current.getParentPath()) {
            if (current.getLeaf() instanceof MethodTree || current.getLeaf() instanceof LambdaExpressionTree) {
                return null;
            }
            if (current.getLeaf() instanceof TryTree) {
                return (TryTree) current.getLeaf();
            }
        }
        return null;
    }
}
