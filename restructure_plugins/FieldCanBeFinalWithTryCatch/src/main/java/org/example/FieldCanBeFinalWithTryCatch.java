package org.example;

import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.util.ASTHelpers.canBeRemoved;
import static com.google.errorprone.util.ASTHelpers.getSymbol;
import static com.google.errorprone.util.ASTHelpers.hasAnnotation;
import static com.google.errorprone.util.ASTHelpers.shouldKeep;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.CompilationUnitTreeMatcher;
import com.google.errorprone.fixes.SuggestedFix;
import com.google.errorprone.fixes.SuggestedFixes;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.*;
import com.sun.source.tree.Tree.Kind;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;

import java.util.*;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

/**
 * @author Liam Miller-Cushon (cushon@google.com)
 */
@AutoService(BugChecker.class)
@BugPattern(
        name = "FieldCanBeFinalWithTryCatch",
        summary = "Field can be converted to a final variable even in the presence of try-catch blocks",
        severity = SUGGESTION,
        documentSuppression = false)
public class FieldCanBeFinalWithTryCatch extends BugChecker implements CompilationUnitTreeMatcher {

    /** Annotations that imply a field is non-constant. */
    // TODO(cushon): consider supporting @Var as a meta-annotation
    private static final ImmutableSet<String> IMPLICIT_VAR_ANNOTATIONS =
            ImmutableSet.of(
                    // keep-sorted start
                    "com.beust.jcommander.Parameter",
                    "com.google.common.annotations.NonFinalForGwt",
                    "com.google.errorprone.annotations.Var",
                    "com.google.gwt.uibinder.client.UiField",
                    "com.google.inject.Inject",
                    "com.google.inject.testing.fieldbinder.Bind",
                    "com.google.testing.junit.testparameterinjector.TestParameter",
                    "javax.inject.Inject",
                    "javax.jdo.annotations.Persistent",
                    "javax.persistence.Id",
                    "javax.xml.bind.annotation.XmlAttribute",
                    "org.kohsuke.args4j.Argument",
                    "org.kohsuke.args4j.Option",
                    "org.mockito.Spy",
                    "picocli.CommandLine.Option"
                    // keep-sorted end
            );

    private static final String OBJECTIFY_PREFIX = "com.googlecode.objectify.";

    /**
     * Annotations that imply a field is non-constant, and that do not have a canonical
     * implementation. Instead, we match on any annotation with one of the following simple names.
     */
    private static final ImmutableSet<String> IMPLICIT_VAR_ANNOTATION_SIMPLE_NAMES =
            ImmutableSet.of("NonFinalForTesting", "NotFinalForTesting");

    /** Unary operator kinds that implicitly assign to their operand. */
    private static final ImmutableSet<Kind> UNARY_ASSIGNMENT =
            Sets.immutableEnumSet(
                    Kind.PREFIX_DECREMENT,
                    Kind.POSTFIX_DECREMENT,
                    Kind.PREFIX_INCREMENT,
                    Kind.POSTFIX_INCREMENT);

    /** The initialization context where an assignment occurred. */
    private enum InitializationContext {
        /** A class (static) initializer. */
        STATIC,
        /** An instance initializer. */
        INSTANCE,
        /** Neither a static or instance initializer. */
        NONE
    }

    /** A record of all assignments to variables in the current compilation unit. */
    private static class VariableAssignmentRecords {

        private final Map<VarSymbol, VariableAssignments> assignments = new LinkedHashMap<>();

        /** Returns all {@link VariableAssignments} in the current compilation unit. */
        private Collection<VariableAssignments> getAssignments() {
            return assignments.values();
        }

        /** Records an assignment to a variable. */
        private void recordAssignment(Tree tree, InitializationContext init, TreePath path) {
            Symbol sym = ASTHelpers.getSymbol(tree);
            if (sym != null && sym.getKind() == ElementKind.FIELD) {
                recordAssignment((VarSymbol) sym, init, path);
            }
        }

        /** Records an assignment to a variable. */
        private void recordAssignment(VarSymbol sym, InitializationContext init, TreePath path) {
            getDeclaration(sym).recordAssignment(init, path);
        }

        private VariableAssignments getDeclaration(VarSymbol sym) {
            return assignments.computeIfAbsent(sym, VariableAssignments::new);
        }

        /** Records a variable declaration. */
        private void recordDeclaration(VarSymbol sym, VariableTree tree) {
            getDeclaration(sym).recordDeclaration(tree);
        }
    }

    /** A record of all assignments to a specific variable in the current compilation unit. */
    private static class VariableAssignments {

        private final VarSymbol sym;
        private final EnumSet<InitializationContext> writes =
                EnumSet.noneOf(InitializationContext.class);
        private VariableTree declaration;
        private final List<TreePath> assignmentPaths = new ArrayList<>();

        VariableAssignments(VarSymbol sym) {
            this.sym = sym;
        }

        /** Records an assignment to the variable. */
        private void recordAssignment(InitializationContext init, TreePath path) {
            writes.add(init);
            assignmentPaths.add(path);
        }

        /** Records that a variable was declared in this compilation unit. */
        private void recordDeclaration(VariableTree tree) {
            declaration = tree;
        }

        /** Returns true if the variable is effectively final. */
        private boolean isEffectivelyFinal() {
            if (declaration == null) {
                return false;
            }
            if (sym.getModifiers().contains(Modifier.FINAL)) {
                // actually final != effectively final
                return false;
            }
            if (writes.contains(InitializationContext.NONE)) {
                return false;
            }
            // The unsound heuristic for effectively final fields is that they are initialized at least
            // once in an initializer with the right static-ness. Multiple initializations are allowed
            // because we don't consider control flow, and zero initializations are allowed to handle
            // class and instance initializers, and delegating constructors that don't initialize the
            // field directly.
            InitializationContext wanted;
            InitializationContext other;
            if (sym.isStatic()) {
                wanted = InitializationContext.STATIC;
                other = InitializationContext.INSTANCE;
            } else {
                wanted = InitializationContext.INSTANCE;
                other = InitializationContext.STATIC;
            }
            if (writes.contains(other)) {
                return false;
            }
            return writes.contains(wanted) || (sym.flags() & Flags.HASINIT) == Flags.HASINIT;
        }

        private VariableTree declaration() {
            return declaration;
        }


        boolean areAllAssignmentsInSameTryBlock() {
            return assignmentPaths.stream()
                    .map(this::getEnclosingTryBlock)
                    .filter(Objects::nonNull)
                    .distinct()
                    .count() == 1;
        }

        public TryTree getEnclosingTryBlock() {
            return assignmentPaths.stream()
                    .map(this::getEnclosingTryBlock)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElseThrow();
        }

        /**
         * Get the enclosing try block for a given path.
         */
        private TryTree getEnclosingTryBlock(TreePath path) {
            while (path != null) {
                if (path.getLeaf().getKind() == Kind.TRY) {
                    return (TryTree) path.getLeaf();
                }
                path = path.getParentPath();
            }
            return null;
        }

        boolean isExplicitlyInitializedToNull() {
            return declaration.getInitializer() != null
                    && declaration.getInitializer().getKind() == Kind.NULL_LITERAL;
        }
    }

    @Override
    public Description matchCompilationUnit(CompilationUnitTree tree, VisitorState state) {
        VariableAssignmentRecords writes = new VariableAssignmentRecords();
        new FinalScanner(writes, state).scan(state.getPath(), InitializationContext.NONE);

        Map<TryTree, List<VariableAssignments>> tryBlockAssignments = new HashMap<>();

        for (VariableAssignments var : writes.getAssignments()) {
            if (!var.isEffectivelyFinal()) {
                continue;
            }
            if (!canBeRemoved(var.sym)) {
                continue;
            }
            if (shouldKeep(var.declaration)) {
                continue;
            }
            if (IMPLICIT_VAR_ANNOTATIONS.stream().anyMatch(a -> hasAnnotation(var.sym, a, state))) {
                continue;
            }
            for (Attribute.Compound anno : var.sym.getAnnotationMirrors()) {
                TypeElement annoElement = (TypeElement) anno.getAnnotationType().asElement();
                if (IMPLICIT_VAR_ANNOTATION_SIMPLE_NAMES.contains(annoElement.getSimpleName().toString())) {
                    return Description.NO_MATCH;
                }
                if (annoElement.getQualifiedName().toString().startsWith(OBJECTIFY_PREFIX)) {
                    return Description.NO_MATCH;
                }
            }
            VariableTree varDecl = var.declaration();


            if (var.areAllAssignmentsInSameTryBlock()) {
                // Find the first enclosing try block
                TryTree enclosingTryBlock = var.getEnclosingTryBlock();
                // Collect variable assignments to handle later
                tryBlockAssignments.computeIfAbsent(enclosingTryBlock, k -> new ArrayList<>()).add(var);
            } else {
                SuggestedFixes.addModifiers(varDecl, state, Modifier.FINAL)
                    .filter(f -> SuggestedFixes.compilesWithFix(f, state))
                    .ifPresent(f -> state.reportMatch(describeMatch(varDecl, f)));
            }
        }
        // Apply modifications to the try blocks and finally blocks collectively
        for (Map.Entry<TryTree, List<VariableAssignments>> entry : tryBlockAssignments.entrySet()) {
            TryTree tryTree = entry.getKey();
            List<VariableAssignments> vars = entry.getValue();

            SuggestedFix.Builder fixBuilder = SuggestedFix.builder();

            // Declare temporary variables before the try block
            for (VariableAssignments var : vars) {
                String tempVarName = "temp" + var.sym.getSimpleName().toString().substring(0, 1).toUpperCase()
                        + var.sym.getSimpleName().toString().substring(1);
                fixBuilder.prefixWith(tryTree, var.sym.asType() + " " + tempVarName + " = null;\n");
                // Add the final modifier to the field declaration
                String varSource = state.getSourceForNode(var.declaration());
                if (var.isExplicitlyInitializedToNull()) {
                    varSource = varSource.replace("= null", "");
                }
                if (!varSource.contains("final")) {
                    varSource = varSource.replaceFirst("\\b(private|protected|public)\\b", "$1 final");
                }
                fixBuilder.replace(var.declaration(), varSource);
            }

            // Replace original variable assignments with temporary variable assignments inside try block
            BlockTree tryBlock = tryTree.getBlock();
            String tryBlockSource = state.getSourceForNode(tryBlock);
            for (VariableAssignments var : vars) {
                String tempVarName = "temp" + var.sym.getSimpleName().toString().substring(0, 1).toUpperCase()
                        + var.sym.getSimpleName().toString().substring(1);
                assert tryBlockSource != null;
                String fieldWithoutThis = "this." + var.sym.getSimpleName().toString();
                tryBlockSource = tryBlockSource.replace(fieldWithoutThis, tempVarName).replace(var.sym.getSimpleName().toString(), tempVarName);
            }
            fixBuilder.replace(tryBlock, tryBlockSource);

            // Modify or add the finally block
            BlockTree finallyBlock = tryTree.getFinallyBlock();
            if (finallyBlock != null) {
                StringBuilder finallyBlockSource = new StringBuilder(Objects.requireNonNull(state.getSourceForNode(finallyBlock)));
                for (VariableAssignments var : vars) {
                    String tempVarName = "temp" + var.sym.getSimpleName().toString().substring(0, 1).toUpperCase()
                            + var.sym.getSimpleName().toString().substring(1);
                    finallyBlockSource.append("\n").append(var.sym.getSimpleName()).append(" = ").append(tempVarName).append(";\n");
                }
                fixBuilder.replace(finallyBlock, finallyBlockSource.toString());
            } else {
                StringBuilder newFinallyBlock = new StringBuilder("finally {\n");
                for (VariableAssignments var : vars) {
                    String tempVarName = "temp" + var.sym.getSimpleName().toString().substring(0, 1).toUpperCase()
                            + var.sym.getSimpleName().toString().substring(1);
                    newFinallyBlock.append(var.sym.getSimpleName()).append(" = ").append(tempVarName).append(";\n");
                }
                newFinallyBlock.append("}\n");
                fixBuilder.postfixWith(tryTree, newFinallyBlock.toString());
            }

            // Apply the try block fix collectively and check if the modified code compiles
            SuggestedFix fix = fixBuilder.build();
            if (SuggestedFixes.compilesWithFix(fix, state)) {
                for (VariableAssignments var : vars) {
                    state.reportMatch(describeMatch(var.declaration(), fix));
                }
            }
        }

        return Description.NO_MATCH;
    }


    /** Record assignments to possibly-final variables in a compilation unit. */
    private class FinalScanner extends TreePathScanner<Void, InitializationContext> {

        private final VariableAssignmentRecords writes;
        private final VisitorState compilationState;

        private FinalScanner(VariableAssignmentRecords writes, VisitorState compilationState) {
            this.writes = writes;
            this.compilationState = compilationState;
        }

        @Override
        public Void visitVariable(VariableTree node, InitializationContext init) {
            VarSymbol sym = ASTHelpers.getSymbol(node);
            if (sym.getKind() == ElementKind.FIELD && !isSuppressed(node, compilationState)) {
                writes.recordDeclaration(sym, node);
            }
            return super.visitVariable(node, InitializationContext.NONE);
        }

        @Override
        public Void visitLambdaExpression(
                LambdaExpressionTree lambdaExpressionTree, InitializationContext init) {
            // reset the initialization context when entering lambda
            return super.visitLambdaExpression(lambdaExpressionTree, InitializationContext.NONE);
        }

        @Override
        public Void visitBlock(BlockTree node, InitializationContext init) {
            if (getCurrentPath().getParentPath().getLeaf().getKind() == Kind.CLASS) {
                init = node.isStatic() ? InitializationContext.STATIC : InitializationContext.INSTANCE;
            }
            return super.visitBlock(node, init);
        }

        @Override
        public Void visitMethod(MethodTree node, InitializationContext init) {
            MethodSymbol sym = ASTHelpers.getSymbol(node);
            if (sym.isConstructor()) {
                init = InitializationContext.INSTANCE;
            }
            return super.visitMethod(node, init);
        }

        @Override
        public Void visitAssignment(AssignmentTree node, InitializationContext init) {
            if (init == InitializationContext.INSTANCE && !isThisAccess(node.getVariable())) {
                // don't record assignments in initializers that aren't to members of the object
                // being initialized
                init = InitializationContext.NONE;
            }
            writes.recordAssignment(node.getVariable(), init, getCurrentPath());
            return super.visitAssignment(node, init);
        }

        private boolean isThisAccess(Tree tree) {
            if (tree.getKind() == Kind.IDENTIFIER) {
                return true;
            }
            if (tree.getKind() != Kind.MEMBER_SELECT) {
                return false;
            }
            ExpressionTree selected = ((MemberSelectTree) tree).getExpression();
            if (!(selected instanceof IdentifierTree)) {
                return false;
            }
            IdentifierTree ident = (IdentifierTree) selected;
            return ident.getName().contentEquals("this");
        }

        @Override
        public Void visitClass(ClassTree node, InitializationContext init) {
            VisitorState state = compilationState.withPath(getCurrentPath());

            if (isSuppressed(node, state)) {
                return null;
            }

            for (Attribute.Compound anno : getSymbol(node).getAnnotationMirrors()) {
                TypeElement annoElement = (TypeElement) anno.getAnnotationType().asElement();
                if (annoElement.getQualifiedName().toString().startsWith(OBJECTIFY_PREFIX)) {
                    return null;
                }
            }

            // reset the initialization context when entering a new declaration
            return super.visitClass(node, InitializationContext.NONE);
        }

        @Override
        public Void visitCompoundAssignment(CompoundAssignmentTree node, InitializationContext init) {
            init = InitializationContext.NONE;
            writes.recordAssignment(node.getVariable(), init, getCurrentPath());
            return super.visitCompoundAssignment(node, init);
        }

        @Override
        public Void visitUnary(UnaryTree node, InitializationContext init) {
            if (UNARY_ASSIGNMENT.contains(node.getKind())) {
                init = InitializationContext.NONE;
                writes.recordAssignment(node.getExpression(), init, getCurrentPath());
            }
            return super.visitUnary(node, init);
        }
    }
}