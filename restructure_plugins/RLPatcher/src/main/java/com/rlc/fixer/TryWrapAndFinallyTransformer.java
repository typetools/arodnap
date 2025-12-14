package com.rlc.fixer;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class TryWrapAndFinallyTransformer {

    // ---------- public API ----------

    public static boolean apply(List<PromptInfo> infos,
            String projectRoot,
            List<String> baselineOutput)
            throws IOException, InterruptedException {
        if (infos == null || infos.isEmpty())
            return false;

        // Prefer bottom-up by start line to minimize index shifts
        infos.sort((a, b) -> Integer.compare(
                safe(b.tryWrapStartLine, b.cfLeakLine),
                safe(a.tryWrapStartLine, a.cfLeakLine)));

        Path src = Paths.get(infos.get(0).leakSourceFile);
        List<String> original = Files.readAllLines(src);

        // -------- Phase 1: try-with-resources wrap for all leaks --------
        CompilationUnit cu = LexicalPreservingPrinter.setup(StaticJavaParser.parse(src));

        for (PromptInfo info : infos) {
            if (info.patchType != PatchType.TRY_WRAP_AND_FINALLY)
                continue;

            Optional<CallableDeclaration> ownerOpt = cu.findFirst(
                    CallableDeclaration.class,
                    m -> beginLine(m) <= safe(info.cfLeakLine, info.tryWrapStartLine)
                            && safe(info.cfLeakLine, info.tryWrapStartLine) <= endLine(m));
            if (ownerOpt.isEmpty())
                continue;

            CallableDeclaration<?> owner = ownerOpt.get();
            applyTryWithResourcesWindow(cu, owner, info);
        }

        // Write, compile, compare to baseline
        Files.write(src, LexicalPreservingPrinter.print(cu).getBytes());
        List<String> patched = CompilerUtils.compile(projectRoot);

        if (!CompilerUtils.outputsDiffer(baselineOutput, patched)) {
            Path backup = Files.createTempFile("orig-", ".java");
            Files.write(backup, original);
            List<String> diff = PatchUtils.diff(backup.toString(), src.toString());
            PatchUtils.writePatch(Paths.get("rlfixer.patch"), diff);
            Files.copy(backup, src, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(backup);
            return true;
        } else {
            Files.write(src, original);
        }

        // -------- Phase 2: classic try+finally wrap (stub for now) --------
        cu = LexicalPreservingPrinter.setup(StaticJavaParser.parse(src));

        for (PromptInfo info : infos) {
            if (info.patchType != PatchType.TRY_WRAP_AND_FINALLY)
                continue;

            Optional<CallableDeclaration> ownerOpt = cu.findFirst(
                    CallableDeclaration.class,
                    m -> beginLine(m) <= safe(info.cfLeakLine, info.tryWrapStartLine)
                            && safe(info.cfLeakLine, info.tryWrapStartLine) <= endLine(m));
            if (ownerOpt.isEmpty())
                continue;

            CallableDeclaration<?> owner = ownerOpt.get();
            // TODO: implement classic try+finally window wrap fallback
            // leave empty per request
            applyTryFinallyWindow(cu, owner, info);
        }

        Files.write(src, LexicalPreservingPrinter.print(cu).getBytes());
        patched = CompilerUtils.compile(projectRoot);

        if (!CompilerUtils.outputsDiffer(baselineOutput, patched)) {
            Path backup = Files.createTempFile("orig-", ".java");
            Files.write(backup, original);
            List<String> diff = PatchUtils.diff(backup.toString(), src.toString());
            PatchUtils.writePatch(Paths.get("rlfixer.patch"), diff);
            Files.copy(backup, src, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(backup);
            return true;
        } else {
            Files.write(src, original);
        }
        return false; // no changes made
    }

    // ---------- TWR window wrap ----------
    private static boolean applyTryWithResourcesWindow(CompilationUnit cu,
            CallableDeclaration<?> owner,
            PromptInfo info) {
        int startLine = info.tryWrapStartLine > 0 ? info.tryWrapStartLine : info.cfLeakLine;
        int initEndLine = info.tryWrapEndLine > 0 ? info.tryWrapEndLine : info.finallyInsertLine;

        // 1) Find smallest BlockStmt spanning [startLine, endLine]
        Optional<BlockStmt> containerOpt = owner.findAll(BlockStmt.class).stream()
                .filter(b -> beginLine(b) <= startLine && initEndLine <= endLine(b))
                .min(Comparator.comparingInt(b -> endLine(b) - beginLine(b)));
        if (containerOpt.isEmpty())
            return false;
        BlockStmt container = containerOpt.get();

        // TEST
        // after computing initial startLine/endLine from RLFixer and picking initial
        // 'container'
        int endLine = expandEndLineForEscapingLocals(container, owner, startLine, initEndLine, info.linesToDelete);
        Optional<BlockStmt> maybe = owner.findAll(BlockStmt.class).stream()
                .filter(b -> beginLine(b) <= startLine && endLine <= endLine(b))
                .min(Comparator.comparingInt(b -> endLine(b) - beginLine(b)));

        if (maybe.isEmpty())
            return false; // can’t form a coherent window
        BlockStmt container2 = maybe.get();
        if (container2 != container)
            container = container2;

        // 2) Select contiguous top-level statements intersecting [startLine, endLine]
        NodeList<Statement> stmts = container.getStatements();
        int from = -1, to = -1;
        for (int i = 0; i < stmts.size(); i++) {
            Statement s = stmts.get(i);
            int b = beginLine(s), e = endLine(s);
            if (b <= endLine && startLine <= e) {
                from = i;
                break;
            }
        }
        if (from < 0)
            return false;
        for (int i = from; i < stmts.size(); i++) {
            Statement s = stmts.get(i);
            int e = endLine(s);
            if (e >= endLine) {
                to = i;
                break;
            }
        }
        if (to < from)
            return false;

        // Keep direct references to the ORIGINAL statements (no cloning)
        java.util.List<Statement> originalSlice = new java.util.ArrayList<>();
        for (int i = from; i <= to; i++)
            originalSlice.add(stmts.get(i));

        // 3) Build new try-with-resources
        TryStmt wrapped = new TryStmt();
        BlockStmt tryBody = new BlockStmt();

        // 4) Resource header: use existing variable if available; otherwise synthesize
        // temp
        Optional<String> varNameOpt = OnlyFinallyTransformer
                .extractLeakedVariableName(cu, info.cfLeakLine, info.allocationExprText);

        VariableDeclarationExpr resourceDecl;
        String resourceVarName;
        List<Statement> notToAddOnTryBody = new ArrayList<>();
        if (varNameOpt.isPresent()) {
            resourceVarName = varNameOpt.get();
            VariableDeclarator vd = owner.findAll(VariableDeclarator.class).stream()
                    .filter(v -> v.getNameAsString().equals(resourceVarName))
                    .findFirst().orElse(null);
            if (vd == null)
                return false;

            resourceDecl = (VariableDeclarationExpr) vd.getParentNode().get();

            // remove original declaration statement; it will live in the TWR header now
            Statement declStmt = resourceDecl.findAncestor(Statement.class).orElse(null);
            if (declStmt != null) {
                notToAddOnTryBody.add(declStmt);
            }

            // no replacement in body needed; code already refers to resourceVarName
        } else {
            // nested allocation – introduce temp var and replace allocation expr inside the
            // slice
            resourceVarName = "__arodnap_temp" + info.index;
            String declSrc = info.resourceType + " " + resourceVarName + " = " + info.allocationExprText + ";";
            resourceDecl = StaticJavaParser
                    .parseBlock("{ " + declSrc + " }")
                    .getStatement(0)
                    .asExpressionStmt()
                    .getExpression()
                    .asVariableDeclarationExpr();

            NameExpr tempRef = new NameExpr(resourceVarName);
            for (Statement s : originalSlice) {
                s.findAll(Expression.class).forEach(expr -> {
                    if (equalsNoSpace(expr.toString(), info.allocationExprText)) {
                        expr.replace(tempRef.clone());
                    }
                });
            }
        }

        NodeList<Expression> resources = new NodeList<>();
        resources.add(resourceDecl.clone());
        wrapped.setResources(resources);

        // 5) Delete any lines requested by RLFixer (remove statement spanning line)
        List<Statement> toDelete = new ArrayList<>();
        for (Integer ln : info.linesToDelete) {
            if (ln == null || ln <= 0)
                continue;
            findInnermostDeletableStmt(owner, ln).ifPresent(toDelete::add);
        }
        // 6) MOVE the original slice into try body (preserve tokens)
        int count = to - from + 1;
        for (int i = 0; i < count; i++) {
            Statement s = stmts.get(from); // list shrinks at 'from'
            s.remove();
            if (notToAddOnTryBody.contains(s)) {
                // don't add the original declaration statement
                continue;
            }
            tryBody.addStatement(s); // move, don't clone
        }
        // delete statements that were requested to be removed
        for (Statement s : toDelete) {
            if (s.getParentNode().isPresent()) {
                s.remove();
            }
        }

        wrapped.setTryBlock(tryBody);

        // 7) Insert wrapped try at the original start
        stmts.add(from, wrapped);

        return true;
    }

    // ---------- helpers ----------

    private static boolean equalsNoSpace(String a, String b) {
        if (a == null || b == null)
            return false;
        return a.replace(" ", "").equals(b.replace(" ", ""));
    }

    private static boolean hasLine(Node n, int line) {
        return n.getRange().map(r -> r.begin.line <= line && line <= r.end.line).orElse(false);
    }

    private static int beginLine(Node n) {
        return n.getRange().map(r -> r.begin.line).orElse(Integer.MAX_VALUE);
    }

    private static int endLine(Node n) {
        return n.getRange().map(r -> r.end.line).orElse(Integer.MIN_VALUE);
    }

    private static int safe(int primary, int fallback) {
        return primary > 0 ? primary : fallback;
    }

    private static Optional<Statement> findInnermostDeletableStmt(Node scope, int line) {
        List<Statement> candidates = scope.findAll(Statement.class).stream()
                .filter(s -> !(s instanceof BlockStmt)) // avoid deleting whole blocks
                .filter(s -> s.getRange().map(r -> r.begin.line == line && r.end.line == line).orElse(false))
                .collect(Collectors.toList());

        // Prefer the smallest-span statement first
        candidates.sort(Comparator.comparingInt(s -> endLine(s) - beginLine(s)));

        // Pick the innermost one (no child statement also spans the same line)
        for (Statement s : candidates) {
            boolean hasDeeper = s.findAll(Statement.class).stream()
                    .filter(t -> t != s && !(t instanceof BlockStmt))
                    .anyMatch(t -> hasLine(t, line));
            if (!hasDeeper)
                return Optional.of(s);
        }

        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
    }

    private static List<VariableDeclarator> declsInWindow(BlockStmt container, int startLine, int endLine) {
        List<VariableDeclarator> out = new ArrayList<>();
        for (Statement s : container.getStatements()) {
            if (!s.getRange().isPresent())
                continue;
            int b = s.getRange().get().begin.line, e = s.getRange().get().end.line;
            if (b <= endLine && startLine <= e) {
                out.addAll(s.findAll(VariableDeclarator.class));
            }
        }
        return out;
    }

    private static int lastUseLineOf(CallableDeclaration<?> owner, String varName) {
        return owner.findAll(com.github.javaparser.ast.expr.NameExpr.class).stream()
                .filter(n -> n.getNameAsString().equals(varName))
                .map(n -> n.getRange().map(r -> r.end.line).orElse(-1))
                .max(Integer::compare)
                .orElse(-1);
    }

    private static boolean applyTryFinallyWindow(CompilationUnit cu,
            CallableDeclaration<?> owner,
            PromptInfo info) {
        int startLine = info.tryWrapStartLine > 0 ? info.tryWrapStartLine : info.cfLeakLine;
        int initEndLine = info.tryWrapEndLine > 0 ? info.tryWrapEndLine : info.finallyInsertLine;

        // 1) Smallest container spanning [startLine, endLine]
        Optional<BlockStmt> containerOpt = owner.findAll(BlockStmt.class).stream()
                .filter(b -> beginLine(b) <= startLine && initEndLine <= endLine(b))
                .min(Comparator.comparingInt(b -> endLine(b) - beginLine(b)));
        if (containerOpt.isEmpty())
            return false;
        BlockStmt container = containerOpt.get();

        // TEST
        // after computing initial startLine/endLine from RLFixer and picking initial
        // 'container'
        int endLine = expandEndLineForEscapingLocals(container, owner, startLine, initEndLine, info.linesToDelete);
        Optional<BlockStmt> maybe = owner.findAll(BlockStmt.class).stream()
                .filter(b -> beginLine(b) <= startLine && endLine <= endLine(b))
                .min(Comparator.comparingInt(b -> endLine(b) - beginLine(b)));

        if (maybe.isEmpty())
            return false; // can’t form a coherent window
        BlockStmt container2 = maybe.get();
        if (container2 != container)
            container = container2;

        // 2) Find contiguous slice [from, to]
        NodeList<Statement> stmts = container.getStatements();
        int from = -1, to = -1;
        for (int i = 0; i < stmts.size(); i++) {
            Statement s = stmts.get(i);
            int b = beginLine(s), e = endLine(s);
            if (b <= endLine && startLine <= e) {
                from = i;
                break;
            }
        }
        if (from < 0)
            return false;
        for (int i = from; i < stmts.size(); i++) {
            Statement s = stmts.get(i);
            int e = endLine(s);
            if (e >= endLine) {
                to = i;
                break;
            }
        }
        if (to < from)
            return false;

        // Keep ORIGINAL nodes (no clones)
        java.util.List<Statement> sliceNodes = new java.util.ArrayList<>();
        for (int i = from; i <= to; i++)
            sliceNodes.add(stmts.get(i));

        // Decide resource variable
        Optional<String> varNameOpt = OnlyFinallyTransformer
                .extractLeakedVariableName(cu, info.cfLeakLine, info.allocationExprText);
        String varName = varNameOpt.orElse("__arodnap_temp" + info.index);

        // 3) Build try/finally
        // 6) finally { if (var != null) { try { var.<finalizer>(); } catch (Exception
        // e) { e.printStackTrace(); } } }
        String args = (info.finalizerDefaultArgs == null || info.finalizerDefaultArgs.isEmpty())
                ? ""
                : String.join(", ", info.finalizerDefaultArgs);
        String catchType = "finalize".equals(info.finalizerMethod)
                ? "Throwable" // finalize can throw Throwable, not just Exception
                : "Exception";
        String finallyBlock = String.join("",
                "finally ",
                "{",
                "  if (", varName, " != null) {",
                "    try { ", varName, ".", info.finalizerMethod, "(", args, "); }",
                "    catch (", catchType, " e) { e.printStackTrace(); }",
                "  }",
                "}");
        // wrapped.setFinallyBlock(StaticJavaParser.parseBlock(finallyBlock));
        // create a skeleton try+finally, then fill its body & resources
        TryStmt wrapped = StaticJavaParser.parseStatement("try { } " + finallyBlock).asTryStmt();
        BlockStmt tryBody = wrapped.getTryBlock();
        tryBody.getStatements().clear();

        

        // 3a) If named resource
        if (varNameOpt.isPresent()) {
            VariableDeclarator vd = owner.findAll(VariableDeclarator.class).stream()
                    .filter(v -> v.getNameAsString().equals(varName))
                    .findFirst().orElse(null);
            if (vd != null) {
                Statement declStmt = vd.findAncestor(Statement.class).orElse(null);
                if (declStmt != null) {
                    boolean declInsideSlice = declStmt.getRange().map(r -> r.begin.line >= beginLine(sliceNodes.get(0))
                            && r.end.line <= endLine(sliceNodes.get(sliceNodes.size() - 1))).orElse(false);

                    if (declInsideSlice) {
                        // Insert "Type var = null;" before try
                        String nullDecl = info.resourceType + " " + varName + " = null;";
                        container.addStatement(from, StaticJavaParser.parseStatement(nullDecl));
                        from++; // shift insertion point
                        to++; // shift end of slice

                        // Turn declaration into assignment (use original initializer)
                        Expression initExpr = vd.getInitializer().map(Expression::clone).orElse(null);
                        VariableDeclarationExpr vde = (VariableDeclarationExpr) vd.getParentNode().get();
                        if (initExpr != null) {
                            if (vde.getVariables().size() == 1) {
                                declStmt.replace(new ExpressionStmt(
                                        new AssignExpr(new NameExpr(varName), initExpr, AssignExpr.Operator.ASSIGN)));
                            } else {
                                vd.setInitializer((Expression) null);
                                BlockStmt blk = (BlockStmt) declStmt.getParentNode().get();
                                int idx = blk.getStatements().indexOf(declStmt);
                                blk.addStatement(idx + 1, new ExpressionStmt(
                                        new AssignExpr(new NameExpr(varName), initExpr, AssignExpr.Operator.ASSIGN)));
                            }
                        } else {
                            // No initializer; keep declaration removed from slice (already null-declared)
                            declStmt.remove();
                        }
                    } else {
                        // Declared outside slice → ensure it is initialized to null
                        if (!vd.getInitializer().isPresent()) {
                            vd.setInitializer(new com.github.javaparser.ast.expr.NullLiteralExpr());
                        }
                    }
                }
            }
        } else {
            // 3b) Nested allocation → declare temp before try
            String nullDecl = info.resourceType + " " + varName + " = null;";
            container.addStatement(from, StaticJavaParser.parseStatement(nullDecl));
            from++; // shift insertion point
            to++; // shift end of slice
        }
        List<Statement> toDelete = new ArrayList<>();
        for (Integer ln : info.linesToDelete) {
            if (ln == null || ln <= 0)
                continue;
            findInnermostDeletableStmt(owner, ln).ifPresent(toDelete::add);
        }

        // 4) MOVE slice statements into try body
        int count = to - from + 1;
        for (int i = 0; i < count; i++) {
            Statement s = stmts.get(from); // list shrinks from 'from'
            s.remove();
            tryBody.addStatement(s);
        }
        // delete statements that were requested to be removed
        for (Statement s : toDelete) {
            if (s.getParentNode().isPresent()) {
                s.remove();
            }
        }
        wrapped.setTryBlock(tryBody);

        // 5) If nested allocation, insert "temp = <allocExpr>;" before the statement
        // that contains it
        if (!varNameOpt.isPresent() && info.allocationExprText != null) {
            Expression allocExpr = wrapped.getTryBlock().findFirst(Expression.class,
                    e -> equalsNoSpace(e.toString(), info.allocationExprText)).orElse(null);
            if (allocExpr != null) {
                // Find the top-level statement in try body containing the allocation
                Node n = allocExpr;
                Statement allocationStmt = null;
                BlockStmt tryBlk = wrapped.getTryBlock();
                while (n != null) {
                    if (n instanceof Statement && n.getParentNode().orElse(null) == tryBlk) {
                        allocationStmt = (Statement) n;
                        break;
                    }
                    n = n.getParentNode().orElse(null);
                }
                if (allocationStmt != null) {
                    String assignSrc = varName + " = " + info.allocationExprText + ";";
                    int idx = tryBlk.getStatements().indexOf(allocationStmt);
                    tryBlk.addStatement(idx, StaticJavaParser.parseStatement(assignSrc));
                    allocExpr.replace(new NameExpr(varName));
                }
            }
        }

        

        // 7) Apply RLFixer delete-line hints (best-effort)

        // 8) Insert wrapped try back where slice began
        container.addStatement(from, wrapped);

        return true;
    }

    private static int expandEndLineForEscapingLocals(BlockStmt container,
            CallableDeclaration<?> owner,
            int startLine,
            int endLine,
            List<Integer> deleteHintLines) {

        int currEnd = endLine;
        int safety = 64; // hard stop to avoid any accidental infinite loop

        while (safety-- > 0) {
            int maxEnd = currEnd;

            // 1) all declarations currently inside [startLine, currEnd]
            List<VariableDeclarator> decls = declsInWindow(container, startLine, currEnd);

            // 2) extend to the last use of each declared variable
            for (VariableDeclarator vd : decls) {
                String name = vd.getNameAsString();
                int lastUse = lastUseLineOf(owner, name);
                if (lastUse > maxEnd)
                    maxEnd = lastUse;
            }

            // 3) keep RLFixer delete-line hints inside the window, too
            if (deleteHintLines != null) {
                for (Integer ln : deleteHintLines) {
                    if (ln != null && ln > maxEnd)
                        maxEnd = ln;
                }
            }

            // 4) fixed point?
            if (maxEnd == currEnd)
                break;
            currEnd = maxEnd;
        }

        // safety check, if endline greater than container end, clamp to container end
        if (currEnd > endLine(container)) {
            currEnd = endLine(container) - 1; // to capture brace at the end of the block
        }

        return currEnd;
    }

}
