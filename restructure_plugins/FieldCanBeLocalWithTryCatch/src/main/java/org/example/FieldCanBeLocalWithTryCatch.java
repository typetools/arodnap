package org.example;


import static com.google.common.collect.Iterables.getLast;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.util.ASTHelpers.getAnnotation;
import static com.google.errorprone.util.ASTHelpers.getStartPosition;
import static com.google.errorprone.util.ASTHelpers.getSymbol;
import static com.google.errorprone.util.ASTHelpers.shouldKeep;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.errorprone.BugPattern;
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
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.ElementKind;

/** Flags fields which can be replaced with local variables. */
@AutoService(BugChecker.class)
@BugPattern(
        name = "FieldCanBeLocalWithTryCatch",
        summary = "Field can be converted to a local variable even in the presence of try-catch blocks",
        severity = SUGGESTION,
        documentSuppression = false)
public final class FieldCanBeLocalWithTryCatch extends BugChecker implements CompilationUnitTreeMatcher {
    private static final ImmutableSet<ElementType> VALID_ON_LOCAL_VARIABLES =
            Sets.immutableEnumSet(ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE);

    @Override
    public Description matchCompilationUnit(CompilationUnitTree tree, VisitorState state) {
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
                        && canBeLocal(variableTree)
                        && !shouldKeep(variableTree)
                        && !symbol.getSimpleName().toString().startsWith("unused")) {
                    potentialFields.put(symbol, getCurrentPath());
                }
                return null;
            }

            private boolean canBeLocal(VariableTree variableTree) {
                if (variableTree.getModifiers() == null) {
                    return true;
                }
                return variableTree.getModifiers().getAnnotations().stream()
                        .allMatch(this::canBeUsedOnLocalVariable);
            }

            private boolean canBeUsedOnLocalVariable(AnnotationTree annotationTree) {
                // TODO(b/137842683): Should this (and all other places using getAnnotation with Target) be
                // replaced with annotation mirror traversals?
                // This is safe given we know that Target does not have Class fields.
                Target target = getAnnotation(annotationTree, Target.class);
                if (target == null) {
                    return true;
                }
                return !Sets.intersection(VALID_ON_LOCAL_VARIABLES, ImmutableSet.copyOf(target.value()))
                        .isEmpty();
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
            String annotations = getAnnotationSource(state, variableTree);
            fix.delete(declarationSite.getLeaf());
            Set<Tree> deletedTrees = new HashSet<>();
            Set<Tree> scopesDeclared = new HashSet<>();
            for (TreePath assignmentSite : assignmentLocations) {
                AssignmentTree assignmentTree = (AssignmentTree) assignmentSite.getLeaf();
                Symbol rhsSymbol = getSymbol(assignmentTree.getExpression());

                // If the RHS of the assignment is a variable with the same name as the field, just remove
                // the assignment.
                String assigneeName = getSymbol(assignmentTree.getVariable()).getSimpleName().toString();
                if (rhsSymbol != null
                        && assignmentTree.getExpression() instanceof IdentifierTree
                        && rhsSymbol.getSimpleName().contentEquals(assigneeName)) {
                    deletedTrees.add(assignmentTree.getVariable());
                    fix.delete(assignmentSite.getParentPath().getLeaf());
                } else {
                    Tree scope = null;
                    boolean isInsideTryCatch = isInsideTryCatch(assignmentSite);
                    if (isInsideTryCatch) {
                        scope = assignmentSite.getParentPath().getParentPath().getParentPath().getLeaf();
                    } else {
                        scope = assignmentSite.getParentPath().getParentPath().getLeaf();
                    }
                    String defaultValue = "null";
                    switch (type) {
                        case "boolean":
                            defaultValue = "false";
                            break;
                        case "byte":
                        case "short":
                        case "int":
                        case "long":
                        case "char":
                            defaultValue = "0";
                            break;
                        case "float":
                        case "double":
                            defaultValue = "0.0";
                            break;
                    }
                    if (scopesDeclared.add(scope)) {
                        if (isInsideTryCatch)   {
                            fix.prefixWith(scope, annotations + " " + type + " " + assigneeName + " = " + defaultValue + ";\n");
                        } else {
                            fix.prefixWith(assignmentSite.getLeaf(), annotations + " " + type + " ");
                        }
                    }
                }
            }
            // Strip "this." off any uses of the field.
            for (Tree usage : uses.get(varSymbol)) {
                if (deletedTrees.contains(usage)
                        || usage.getKind() == Kind.IDENTIFIER
                        || usage.getKind() != Kind.MEMBER_SELECT) {
                    continue;
                }
                ExpressionTree selected = ((MemberSelectTree) usage).getExpression();
                if (!(selected instanceof IdentifierTree)) {
                    continue;
                }
                IdentifierTree ident = (IdentifierTree) selected;
                if (ident.getName().contentEquals("this")) {
                    fix.replace(getStartPosition(ident), state.getEndPosition(ident) + 1, "");
                }
            }
            state.reportMatch(describeMatch(declarationSite.getLeaf(), fix.build()));
        }
        return Description.NO_MATCH;
    }

    private boolean isInsideTryCatch(TreePath path) {
        while (path != null) {
            if (path.getLeaf().getKind() == Tree.Kind.TRY) {
                return true;
            }
            path = path.getParentPath();
        }
        return false;
    }

    private static String getAnnotationSource(VisitorState state, VariableTree variableTree) {
        List<? extends AnnotationTree> annotations = variableTree.getModifiers().getAnnotations();
        if (annotations == null || annotations.isEmpty()) {
            return "";
        }
        return state
                .getSourceCode()
                .subSequence(
                        getStartPosition(annotations.get(0)), state.getEndPosition(getLast(annotations)))
                .toString();
    }

    public static void printObjectReference(Object obj) {
        // Print the class name and the identity hash code (a representation of the object's reference)
        System.out.println("Object reference: " + obj.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(obj)));
    }
}
