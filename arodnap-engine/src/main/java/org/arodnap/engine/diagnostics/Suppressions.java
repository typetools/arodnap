package org.arodnap.engine.diagnostics;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Where a project's developers marked resource warnings as intended: declarations (classes,
 * methods, constructors, fields and variables) annotated {@code @SuppressWarnings("resource")},
 * the key javac, Eclipse and IntelliJ use, or {@code @SuppressWarnings("all")}. The Resource Leak
 * Checker only honors its own keys, so it still reports leaks there; Arodnap reports them but does
 * not repair them, since the code there often hands its resource to a caller on purpose.
 */
public final class Suppressions {
    /** The {@code @SuppressWarnings} values that mark resource warnings as intended. */
    public static final Set<String> KEYS = Set.of("resource", "all");

    private record Range(long first, long last) {}

    private final Map<Path, List<Range>> ranges;

    private Suppressions(Map<Path, List<Range>> ranges) {
        this.ranges = ranges;
    }

    /** The suppressed declarations in {@code files}, read with the JDK's parser. */
    public static Suppressions find(Set<Path> files, Charset encoding) throws IOException {
        Map<Path, List<Range>> ranges = new HashMap<>();
        if (files.isEmpty()) {
            return new Suppressions(ranges);
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new IOException("Arodnap needs a JDK, not just a Java runtime, to read which code is marked @SuppressWarnings(\"resource\").");
        }
        try (StandardJavaFileManager fileManager = javac.getStandardFileManager(null, null, encoding)) {
            JavacTask task = (JavacTask) javac.getTask(null, fileManager, diagnostic -> {}, List.of("-proc:none"), null,
                    fileManager.getJavaFileObjectsFromPaths(files));
            SourcePositions positions = Trees.instance(task).getSourcePositions();
            for (CompilationUnitTree unit : task.parse()) {
                List<Range> suppressed = new ArrayList<>();
                LineMap lines = unit.getLineMap();
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitClass(ClassTree node, Void unused) {
                        check(node, node.getModifiers());
                        return super.visitClass(node, unused);
                    }

                    @Override
                    public Void visitMethod(MethodTree node, Void unused) {
                        check(node, node.getModifiers());
                        return super.visitMethod(node, unused);
                    }

                    @Override
                    public Void visitVariable(VariableTree node, Void unused) {
                        check(node, node.getModifiers());
                        return super.visitVariable(node, unused);
                    }

                    private void check(Tree node, ModifiersTree modifiers) {
                        if (modifiers != null && suppressesResourceWarnings(modifiers)) {
                            long start = positions.getStartPosition(unit, node);
                            long end = positions.getEndPosition(unit, node);
                            if (start >= 0 && end >= start) {
                                suppressed.add(new Range(lines.getLineNumber(start), lines.getLineNumber(end)));
                            }
                        }
                    }
                }.scan(unit, null);
                if (!suppressed.isEmpty()) {
                    ranges.put(Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize(), suppressed);
                }
            }
        }
        return new Suppressions(ranges);
    }

    /** True when {@code line} of {@code file} is inside a suppressed declaration. */
    public boolean covers(Path file, int line) {
        return ranges.getOrDefault(file.toAbsolutePath().normalize(), List.of()).stream()
                .anyMatch(range -> range.first() <= line && line <= range.last());
    }

    /** The checker's output without the warnings in suppressed declarations: what the repair stages see. */
    public String repairable(String diagnostics) {
        List<CheckerWarning.Block> blocks = CheckerWarning.blocks(diagnostics);
        StringBuilder kept = new StringBuilder();
        int from = 0;
        for (CheckerWarning.Block block : blocks) {
            if (covers(Path.of(block.warning().file()), block.warning().line())) {
                kept.append(diagnostics, from, block.start());
                from = block.end();
            }
        }
        return kept.append(diagnostics.substring(from)).toString();
    }

    /** The source files the checker's output has warnings in. */
    public static Set<Path> filesWithWarnings(String diagnostics) {
        Set<Path> files = new LinkedHashSet<>();
        for (CheckerWarning warning : CheckerWarning.parseAll(diagnostics)) {
            files.add(Path.of(warning.file()));
        }
        return files;
    }

    private static boolean suppressesResourceWarnings(ModifiersTree modifiers) {
        for (AnnotationTree annotation : modifiers.getAnnotations()) {
            String name = annotation.getAnnotationType().toString();
            if (!name.equals("SuppressWarnings") && !name.equals("java.lang.SuppressWarnings")) {
                continue;
            }
            for (ExpressionTree argument : annotation.getArguments()) {
                ExpressionTree value = argument instanceof AssignmentTree assignment ? assignment.getExpression() : argument;
                List<? extends ExpressionTree> values = value instanceof NewArrayTree array && array.getInitializers() != null
                        ? array.getInitializers() : List.of(value);
                for (ExpressionTree each : values) {
                    if (each instanceof LiteralTree literal && literal.getValue() instanceof String key && KEYS.contains(key)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
