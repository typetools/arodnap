package org.arodnap.fields;

import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.util.ASTHelpers.canBeRemoved;
import static com.google.errorprone.util.ASTHelpers.getSymbol;
import static com.google.errorprone.util.ASTHelpers.hasAnnotation;
import static com.google.errorprone.util.ASTHelpers.shouldKeep;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.errorprone.BugPattern;
import com.google.errorprone.ErrorProneFlags;
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
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;

import java.util.*;
import javax.inject.Inject;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

/**
 * Makes private resource fields {@code final} when they are assigned only once, during
 * construction (Arodnap's "preventing reassignment" transformation). Based on Error Prone's
 * FieldCanBeFinal by Liam Miller-Cushon, extended to fields assigned inside {@code try}.
 *
 * <p>Only fields whose type can hold a resource are changed (see {@link ResourceFields}), and
 * every change is compile-checked for its file. Making a field final never changes behavior:
 * the compiler rejects any later write.
 */
@AutoService(BugChecker.class)
@BugPattern(
        name = "ResourceFieldCanBeFinal",
        summary = "Resource field can be final",
        severity = SUGGESTION,
        documentSuppression = false)
public class ResourceFieldCanBeFinal extends BugChecker implements CompilationUnitTreeMatcher {

    private final List<String> extraResourceTypes;
    private final boolean allFields;

    /** For {@link java.util.ServiceLoader}, which Error Prone uses to find plugins. */
    public ResourceFieldCanBeFinal() {
        this(ErrorProneFlags.empty());
    }

    @Inject
    public ResourceFieldCanBeFinal(ErrorProneFlags flags) {
        extraResourceTypes = Edits.extraResourceTypes(flags);
        allFields = Edits.allFields(flags);
    }

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
        ResourceFields resourceFields = ResourceFields.of(state, extraResourceTypes);
        VariableAssignmentRecords writes = new VariableAssignmentRecords();
        new FinalScanner(writes, state).scan(state.getPath(), InitializationContext.NONE);

        Map<TryTree, List<VariableAssignments>> tryBlockAssignments = new LinkedHashMap<>();

        for (VariableAssignments var : writes.getAssignments()) {
            if (!var.isEffectivelyFinal()) {
                continue;
            }
            if (!canBeRemoved(var.sym)) {
                continue;
            }
            if (!var.sym.isPrivate() || !(allFields || resourceFields.isRelevant(var.sym.type))) {
                // Only private fields holding a resource: nothing outside this file can assign
                // them, and nothing else matters for resource-leak analysis.
                continue;
            }
            if (shouldKeep(var.declaration)) {
                continue;
            }
            if (IMPLICIT_VAR_ANNOTATIONS.stream().anyMatch(a -> hasAnnotation(var.sym, a, state))) {
                continue;
            }
            boolean implicitlyVariable = false;
            for (Attribute.Compound anno : var.sym.getAnnotationMirrors()) {
                TypeElement annoElement = (TypeElement) anno.getAnnotationType().asElement();
                if (IMPLICIT_VAR_ANNOTATION_SIMPLE_NAMES.contains(annoElement.getSimpleName().toString())
                        || annoElement.getQualifiedName().toString().startsWith(OBJECTIFY_PREFIX)) {
                    implicitlyVariable = true;
                }
            }
            if (implicitlyVariable) {
                continue;
            }
            VariableTree varDecl = var.declaration();

            if (var.areAllAssignmentsInSameTryBlock()) {
                tryBlockAssignments.computeIfAbsent(var.getEnclosingTryBlock(), k -> new ArrayList<>()).add(var);
            } else {
                SuggestedFix fix = SuggestedFix.builder().prefixWith(varDecl.getType(), "final ").build();
                if (SuggestedFixes.compilesWithFix(fix, state)) {
                    state.reportMatch(describe(varDecl, var.sym, fix));
                }
            }
        }
        for (Map.Entry<TryTree, List<VariableAssignments>> entry : tryBlockAssignments.entrySet()) {
            tryBlockFix(entry.getKey(), entry.getValue(), state).ifPresent(fix -> {
                for (VariableAssignments var : entry.getValue()) {
                    state.reportMatch(describe(var.declaration(), var.sym, fix));
                }
            });
        }
        return Description.NO_MATCH;
    }

    private Description describe(VariableTree declaration, VarSymbol sym, SuggestedFix fix) {
        return buildDescription(declaration)
                .setMessage("Resource field `" + sym.getSimpleName() + "` can be final")
                .addFix(fix)
                .build();
    }

    /**
     * A field assigned inside a {@code try} cannot simply become final: Java requires a blank
     * final to be definitely assigned, which fails when the assignment can throw. The rewrite
     * assigns a temporary inside the {@code try} and the field in {@code finally}:
     *
     * <pre>
     * Socket tempSocket = null;
     * try {
     *     tempSocket = new Socket(host, port);
     * } catch (IOException e) { ... } finally {
     *     this.socket = tempSocket;
     * }
     * </pre>
     *
     * The field is then assigned later than before, so the rewrite is only made when nothing
     * between the original assignment and the {@code finally} (the rest of the try block, the
     * catch blocks) can observe the field: no calls of this object's methods, no {@code this},
     * no lambdas or inner classes.
     */
    private Optional<SuggestedFix> tryBlockFix(TryTree tryTree, List<VariableAssignments> vars, VisitorState state) {
        if (!tryTree.getResources().isEmpty()) {
            return Optional.empty();
        }
        Set<VarSymbol> symbols = new HashSet<>();
        for (VariableAssignments var : vars) {
            symbols.add(var.sym);
        }
        List<? extends StatementTree> statements = tryTree.getBlock().getStatements();
        int firstAssignment = -1;
        for (int i = 0; i < statements.size() && firstAssignment < 0; i++) {
            if (assignsAny(statements.get(i), symbols)) {
                firstAssignment = i;
            }
        }
        if (firstAssignment < 0) {
            return Optional.empty();
        }
        for (int i = firstAssignment; i < statements.size(); i++) {
            StatementTree statement = statements.get(i);
            // A plain "field = expression;" evaluates the expression before assigning.
            boolean plainAssignment = statement instanceof ExpressionStatementTree
                    && ((ExpressionStatementTree) statement).getExpression() instanceof AssignmentTree
                    && symbols.contains(ASTHelpers.getSymbol(
                            ((AssignmentTree) ((ExpressionStatementTree) statement).getExpression()).getVariable()));
            if (!plainAssignment && mayObserveObject(statement)) {
                return Optional.empty();
            }
        }
        for (CatchTree catchTree : tryTree.getCatches()) {
            if (mayObserveObject(catchTree.getBlock())) {
                return Optional.empty();
            }
        }

        SuggestedFix.Builder fix = SuggestedFix.builder();
        String indent = Edits.indentAt(state, ASTHelpers.getStartPosition(tryTree));
        String unit = Edits.indentUnit(state, tryTree, statements.get(0));
        StringBuilder declarations = new StringBuilder();
        StringBuilder finallyAssignments = new StringBuilder();
        Map<VarSymbol, String> temps = new HashMap<>();
        for (VariableAssignments var : vars) {
            String name = var.sym.getSimpleName().toString();
            String temp = Edits.freshName(state, "temp" + Character.toUpperCase(name.charAt(0)) + name.substring(1));
            temps.put(var.sym, temp);
            String type = SuggestedFixes.prettyType(state, fix, var.sym.type);
            declarations.append(type).append(' ').append(temp).append(" = null;\n").append(indent);
            String target = var.sym.isStatic() ? name : "this." + name;
            finallyAssignments.append('\n').append(indent).append(unit).append(target).append(" = ").append(temp).append(';');

            VariableTree declaration = var.declaration();
            fix.prefixWith(declaration.getType(), "final ");
            if (var.isExplicitlyInitializedToNull()) {
                int nameEnd = ((JCTree.JCVariableDecl) declaration).pos + name.length();
                fix.replace(nameEnd, state.getEndPosition(declaration.getInitializer()), "");
            }
        }
        fix.prefixWith(tryTree, declarations.toString());

        // Every reference to the fields inside the try block now uses the temporaries.
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                replace(node);
                return null;
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                if (!replace(node)) {
                    super.visitMemberSelect(node, null);
                }
                return null;
            }

            private boolean replace(Tree node) {
                Symbol symbol = ASTHelpers.getSymbol(node);
                if (symbol instanceof VarSymbol && temps.containsKey(symbol)) {
                    fix.replace(node, temps.get(symbol));
                    return true;
                }
                return false;
            }
        }.scan(tryTree.getBlock(), null);

        BlockTree finallyBlock = tryTree.getFinallyBlock();
        if (finallyBlock != null) {
            // First in the finally block, so the rest of it sees the field assigned.
            int brace = ASTHelpers.getStartPosition(finallyBlock);
            fix.replace(brace, brace + 1, "{" + finallyAssignments);
        } else {
            fix.postfixWith(tryTree, " finally {" + finallyAssignments + "\n" + indent + "}");
        }
        SuggestedFix built = fix.build();
        return SuggestedFixes.compilesWithFix(built, state) ? Optional.of(built) : Optional.empty();
    }

    private static boolean isThisOrSuper(Tree tree) {
        return tree instanceof IdentifierTree
                && (((IdentifierTree) tree).getName().contentEquals("this")
                        || ((IdentifierTree) tree).getName().contentEquals("super"));
    }

    private static boolean assignsAny(StatementTree statement, Set<VarSymbol> symbols) {
        Boolean found = new TreeScanner<Boolean, Void>() {
            @Override
            public Boolean visitAssignment(AssignmentTree node, Void unused) {
                return symbols.contains(ASTHelpers.getSymbol(node.getVariable())) || super.visitAssignment(node, null);
            }

            @Override
            public Boolean reduce(Boolean a, Boolean b) {
                return Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b);
            }
        }.scan(statement, null);
        return Boolean.TRUE.equals(found);
    }

    /**
     * Conservatively, whether running {@code tree} could read one of this object's fields (or a
     * static field of this class) through anything but a direct reference: a call of a method
     * without an explicit receiver or on {@code this}/{@code super}, a use of {@code this}, a
     * lambda, a method reference or a class creation that may capture it.
     */
    private static boolean mayObserveObject(Tree tree) {
        Boolean found = new TreeScanner<Boolean, Void>() {
            @Override
            public Boolean visitMethodInvocation(MethodInvocationTree node, Void unused) {
                ExpressionTree select = node.getMethodSelect();
                if (select instanceof IdentifierTree) {
                    return true;
                }
                if (select instanceof MemberSelectTree && isThisOrSuper(((MemberSelectTree) select).getExpression())) {
                    return true;
                }
                return super.visitMethodInvocation(node, null);
            }

            @Override
            public Boolean visitMemberSelect(MemberSelectTree node, Void unused) {
                // this.field reads or writes a field directly; it does not hand the object out.
                if (isThisOrSuper(node.getExpression()) && ASTHelpers.getSymbol(node) instanceof VarSymbol) {
                    return false;
                }
                return super.visitMemberSelect(node, null);
            }

            @Override
            public Boolean visitIdentifier(IdentifierTree node, Void unused) {
                return isThisOrSuper(node);
            }

            @Override
            public Boolean visitNewClass(NewClassTree node, Void unused) {
                if (node.getClassBody() != null) {
                    return true;
                }
                Symbol constructed = ASTHelpers.getSymbol(node.getIdentifier());
                if (constructed != null && constructed.hasOuterInstance()) {
                    return true;
                }
                return super.visitNewClass(node, null);
            }

            @Override
            public Boolean visitLambdaExpression(LambdaExpressionTree node, Void unused) {
                return true;
            }

            @Override
            public Boolean visitMemberReference(MemberReferenceTree node, Void unused) {
                return true;
            }

            @Override
            public Boolean reduce(Boolean a, Boolean b) {
                return Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b);
            }
        }.scan(tree, null);
        return Boolean.TRUE.equals(found);
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