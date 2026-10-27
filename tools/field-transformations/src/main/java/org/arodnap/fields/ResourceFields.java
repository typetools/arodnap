package org.arodnap.fields;

import com.google.errorprone.VisitorState;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.util.Context;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import javax.lang.model.element.ElementKind;
import javax.lang.model.type.TypeKind;
import javax.tools.JavaFileObject;

/**
 * Which field types can hold a resource that must be released, so that the field
 * transformations only touch fields that matter for resource-leak analysis.
 *
 * <p>A type's <em>cleanup methods</em> are the methods that release what it holds:
 *
 * <ul>
 *   <li>{@code close} for subtypes of {@link AutoCloseable};
 *   <li>for the extra resource types passed in (types the Checker Framework's annotated JDK
 *       marks {@code @MustCall} without being {@code AutoCloseable}), the given methods;
 *   <li>for types annotated {@code @MustCall}/{@code @InheritableMustCall}, the listed methods;
 *   <li>for a class of the program being compiled, each of its methods that disposes of one of
 *       its resource fields: calls a cleanup method of the field's type on it, or passes it to a
 *       method that disposes of that parameter (in the program, recursively, or annotated
 *       {@code @EnsuresCalledMethods} in a library). The name does not matter.
 *   <li>{@code close} for a program class whose constructor stores a newly created resource in a
 *       field: Arodnap's close injection adds that method.
 * </ul>
 *
 * <p>Cleanup methods are inherited, and wrappers of wrappers follow from the fixpoint. This
 * mirrors how the Resource Leak Checker's inference decides that a field is {@code @Owning} (the
 * class calls the field's must-call method) and so which classes get an obligation. A field's
 * type is <em>relevant</em> when it has a cleanup method. Arrays, collections and types without
 * cleanup methods (such as {@code Object} or plain interfaces) are not: the checker does not
 * attach obligations to them.
 */
final class ResourceFields {

    private static final Map<Context, ResourceFields> CACHE = new WeakHashMap<>();
    private static final Set<String> MUST_CALL_ANNOTATIONS = Set.of(
            "org.checkerframework.checker.mustcall.qual.MustCall",
            "org.checkerframework.checker.mustcall.qual.InheritableMustCall");
    private static final String ENSURES_CALLED_METHODS =
            "org.checkerframework.checker.calledmethods.qual.EnsuresCalledMethods";

    private final Types types;
    private final JavacTrees trees;
    private final Type autoCloseable;
    private final Map<Type, Set<String>> extraResourceTypes = new HashMap<>();
    /** Cleanup methods found for program classes. */
    private final Map<Symbol, Set<String>> programCleanup = new HashMap<>();
    /** Program methods that dispose of some of their parameters (by index). */
    private final Map<MethodSymbol, Set<Integer>> disposedParameters = new HashMap<>();
    private final Map<Type, Set<String>> cleanupCache = new HashMap<>();
    private boolean changed;

    /** The analysis for the current compilation, computed once over all of its classes. */
    static synchronized ResourceFields of(VisitorState state, List<String> extraResourceTypeSpecs) {
        return CACHE.computeIfAbsent(state.context, context -> new ResourceFields(state, extraResourceTypeSpecs));
    }

    /** @param extraResourceTypeSpecs entries {@code type:method1|method2}, e.g. {@code java.net.HttpURLConnection:disconnect} */
    private ResourceFields(VisitorState state, List<String> extraResourceTypeSpecs) {
        Symtab symtab = state.getSymtab();
        types = state.getTypes();
        trees = JavacTrees.instance(state.context);
        autoCloseable = types.erasure(symtab.autoCloseableType);
        for (String spec : extraResourceTypeSpecs) {
            String[] parts = spec.split(":", 2);
            Type type = state.getTypeFromString(parts[0].trim());
            if (type != null && parts.length == 2) {
                extraResourceTypes.put(types.erasure(type), Set.of(parts[1].trim().split("\\|")));
            }
        }
        List<JCTree.JCClassDecl> program = new ArrayList<>();
        for (ClassSymbol symbol : symtab.getAllClasses()) {
            if (isFromSource(symbol)) {
                JCTree.JCClassDecl tree = trees.getTree(symbol);
                if (tree != null) {
                    program.add(tree);
                }
            }
        }
        do {
            changed = false;
            cleanupCache.clear();
            for (JCTree.JCClassDecl tree : program) {
                scanClass(tree);
            }
        } while (changed);
        cleanupCache.clear();
    }

    /** True if a field declared with {@code type} can hold a resource that must be released. */
    boolean isRelevant(Type type) {
        return !cleanupMethods(type).isEmpty();
    }

    private Set<String> cleanupMethods(Type type) {
        if (type == null) {
            return Set.of();
        }
        Type erased = types.erasure(type);
        if (erased.isPrimitive() || erased.getKind() == TypeKind.ARRAY || erased.tsym == null) {
            return Set.of();
        }
        Set<String> cached = cleanupCache.get(erased);
        if (cached != null) {
            return cached;
        }
        Set<String> methods = new HashSet<>();
        if (types.isSubtype(erased, autoCloseable)) {
            methods.add("close");
        }
        for (Map.Entry<Type, Set<String>> extra : extraResourceTypes.entrySet()) {
            if (types.isSubtype(erased, extra.getKey())) {
                methods.addAll(extra.getValue());
            }
        }
        for (Type supertype : types.closure(erased)) {
            methods.addAll(programCleanup.getOrDefault(supertype.tsym, Set.of()));
            for (Attribute.Compound annotation : supertype.tsym.getAnnotationMirrors()) {
                if (MUST_CALL_ANNOTATIONS.contains(annotation.type.tsym.getQualifiedName().toString())) {
                    methods.addAll(stringValues(annotation));
                }
            }
        }
        cleanupCache.put(erased, methods);
        return methods;
    }

    private void scanClass(JCTree.JCClassDecl classTree) {
        ClassSymbol owner = classTree.sym;
        for (JCTree member : classTree.defs) {
            if (!(member instanceof JCTree.JCMethodDecl) || ((JCTree.JCMethodDecl) member).body == null) {
                continue;
            }
            JCTree.JCMethodDecl method = (JCTree.JCMethodDecl) member;
            boolean constructor = TreeInfo.isConstructor(method);
            method.body.accept(new TreeScanner() {
                @Override
                public void visitClassDef(JCTree.JCClassDecl nested) {
                    // Nested and anonymous classes are scanned as classes of their own.
                }

                @Override
                public void visitApply(JCTree.JCMethodInvocation invocation) {
                    super.visitApply(invocation);
                    Symbol called = TreeInfo.symbol(invocation.meth);
                    if (!(called instanceof MethodSymbol)) {
                        return;
                    }
                    // receiver.cleanup()
                    if (invocation.meth instanceof JCTree.JCFieldAccess) {
                        Symbol receiver = TreeInfo.symbol(((JCTree.JCFieldAccess) invocation.meth).selected);
                        if (receiver instanceof VarSymbol
                                && cleanupMethods(receiver.type).contains(called.getSimpleName().toString())) {
                            disposes((VarSymbol) receiver);
                        }
                    }
                    // helper(receiver): a method that disposes of that parameter
                    MethodSymbol callee = (MethodSymbol) called;
                    for (int i = 0; i < invocation.args.size(); i++) {
                        Symbol argument = TreeInfo.symbol(invocation.args.get(i));
                        if (argument instanceof VarSymbol && disposesParameter(callee, i)) {
                            disposes((VarSymbol) argument);
                        }
                    }
                }

                @Override
                public void visitAssign(JCTree.JCAssign assign) {
                    super.visitAssign(assign);
                    // A constructor storing a new resource in a field: close injection applies.
                    Symbol target = TreeInfo.symbol(assign.lhs);
                    JCTree.JCExpression value = TreeInfo.skipParens(assign.rhs);
                    if (constructor && isOwnField(target)
                            && (value instanceof JCTree.JCNewClass || value instanceof JCTree.JCMethodInvocation)
                            && isRelevant(value.type)) {
                        addCleanup(owner, "close");
                    }
                }

                private void disposes(VarSymbol variable) {
                    if (isOwnField(variable) && !constructor) {
                        addCleanup(owner, method.name.toString());
                    } else if (variable.getKind() == ElementKind.PARAMETER && variable.owner == method.sym) {
                        int index = method.sym.getParameters().indexOf(variable);
                        if (index >= 0 && disposedParameters.computeIfAbsent(method.sym, k -> new HashSet<>()).add(index)) {
                            changed = true;
                        }
                    }
                }

                private boolean isOwnField(Symbol symbol) {
                    return symbol instanceof VarSymbol
                            && symbol.getKind() == ElementKind.FIELD
                            && !symbol.isStatic()
                            && owner.isSubClass(symbol.owner, types);
                }
            });
        }
    }

    private boolean disposesParameter(MethodSymbol callee, int index) {
        if (disposedParameters.getOrDefault(callee, Set.of()).contains(index)) {
            return true;
        }
        for (Attribute.Compound annotation : callee.getAnnotationMirrors()) {
            if (annotation.type.tsym.getQualifiedName().contentEquals(ENSURES_CALLED_METHODS)) {
                return true; // library helpers such as closeQuietly(...)
            }
        }
        return false;
    }

    private void addCleanup(Symbol owner, String method) {
        if (programCleanup.computeIfAbsent(owner, k -> new HashSet<>()).add(method)) {
            changed = true;
            cleanupCache.clear();
        }
    }

    private static Set<String> stringValues(Attribute.Compound annotation) {
        Set<String> values = new HashSet<>();
        for (com.sun.tools.javac.util.Pair<MethodSymbol, Attribute> entry : annotation.values) {
            if (!entry.fst.getSimpleName().contentEquals("value")) {
                continue;
            }
            if (entry.snd instanceof Attribute.Array) {
                for (Attribute element : ((Attribute.Array) entry.snd).values) {
                    values.add(String.valueOf(element.getValue()));
                }
            } else if (entry.snd instanceof Attribute.Constant) {
                values.add(String.valueOf(entry.snd.getValue()));
            }
        }
        values.remove("");
        return values;
    }

    private static boolean isFromSource(ClassSymbol symbol) {
        return symbol.sourcefile != null
                && symbol.sourcefile.getKind() == JavaFileObject.Kind.SOURCE
                && (symbol.classfile == null || symbol.classfile.getKind() == JavaFileObject.Kind.SOURCE);
    }
}
