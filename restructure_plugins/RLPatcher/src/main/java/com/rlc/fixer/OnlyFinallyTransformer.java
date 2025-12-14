package com.rlc.fixer;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.Node;
import java.util.List;
import java.util.Optional;

public class OnlyFinallyTransformer {

    private static int beginLine(Node n) {
        return n.getRange().map(r -> r.begin.line).orElse(Integer.MAX_VALUE);
    }

    private static int endLine(Node n) {
        return n.getRange().map(r -> r.end.line).orElse(Integer.MIN_VALUE);
    }

    public static boolean apply(List<PromptInfo> infos,
            String projectRoot,
            List<String> baselineOutput)
            throws java.io.IOException, InterruptedException {
        if (infos == null || infos.isEmpty())
            return false;

        // Sort descending by CF line so earlier edits don't shift later targets
        infos.sort((a, b) -> Integer.compare(b.cfLeakLine, a.cfLeakLine));

        java.nio.file.Path src = java.nio.file.Paths.get(infos.get(0).leakSourceFile);
        java.util.List<String> original = java.nio.file.Files.readAllLines(src);

        // -------- Phase 1: Try-with-resources for all leaks --------
        {
            com.github.javaparser.ast.CompilationUnit cu = com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
                    .setup(
                            com.github.javaparser.StaticJavaParser.parse(src));

            // Pre-resolve target try statements in a list parallel to infos
            // java.util.List<com.github.javaparser.ast.stmt.TryStmt> tryList =
            // resolveTryStmts(cu, infos);

            // Apply TWR edits using pre-bound try nodes
            for (int i = 0; i < infos.size(); i++) {
                PromptInfo info = infos.get(i);

                // Re-locate owner method fresh (ranges may shift as we edit)
                java.util.Optional<com.github.javaparser.ast.body.CallableDeclaration> ownerOpt = cu.findFirst(
                        com.github.javaparser.ast.body.CallableDeclaration.class,
                        m -> beginLine(m) <= info.cfLeakLine && info.cfLeakLine <= endLine(m));
                if (ownerOpt.isEmpty())
                    continue;

                com.github.javaparser.ast.body.CallableDeclaration<?> owner = ownerOpt.get();

                com.github.javaparser.ast.stmt.TryStmt targetTry = findTargetTryStmt(owner, info.cfLeakLine,
                        info.finallyInsertLine).orElse(null);
                if (targetTry == null)
                    continue; // nothing to do for this leak

                // Use the overload that accepts a pre-resolved TryStmt
                applyTryWithResources(cu, owner, info, targetTry);
            }

            // Write, compile, check against baseline
            java.nio.file.Files.write(src,
                    com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter.print(cu).getBytes());
            java.util.List<String> patched = CompilerUtils.compile(projectRoot);

            if (!CompilerUtils.outputsDiffer(baselineOutput, patched)) {
                // Success → emit single patch and restore original file
                java.nio.file.Path backup = java.nio.file.Files.createTempFile("orig-", ".java");
                java.nio.file.Files.write(backup, original);
                java.util.List<String> diff = PatchUtils.diff(backup.toString(), src.toString());
                PatchUtils.writePatch(java.nio.file.Paths.get("rlfixer.patch"), diff);
                java.nio.file.Files.copy(backup, src, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                java.nio.file.Files.deleteIfExists(backup);
                return true;
            } else {
                // Revert to pristine and proceed to Phase 2
                java.nio.file.Files.write(src, original);
            }
        }

        // -------- Phase 2: Classic try–finally for all leaks --------
        {
            com.github.javaparser.ast.CompilationUnit cu = com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
                    .setup(
                            com.github.javaparser.StaticJavaParser.parse(src));

            // IMPORTANT: Re-resolve try statements on the fresh AST
            // java.util.List<com.github.javaparser.ast.stmt.TryStmt> tryList = new
            // ArrayList<>();
            // for (PromptInfo info : infos) {
            // tryList.add(findTargetTryStmt(cu, info.cfLeakLine, info.finallyInsertLine));
            // }

            for (int i = 0; i < infos.size(); i++) {
                PromptInfo info = infos.get(i);
                // com.github.javaparser.ast.stmt.TryStmt targetTry = tryList.get(i);
                // if (targetTry == null)
                // continue;

                java.util.Optional<com.github.javaparser.ast.body.CallableDeclaration> ownerOpt = cu.findFirst(
                        com.github.javaparser.ast.body.CallableDeclaration.class,
                        m -> beginLine(m) <= info.cfLeakLine && info.cfLeakLine <= endLine(m));
                if (ownerOpt.isEmpty())
                    continue;
                com.github.javaparser.ast.body.CallableDeclaration<?> owner = ownerOpt.get();

                com.github.javaparser.ast.stmt.TryStmt targetTry = findTargetTryStmt(owner, info.cfLeakLine,
                        info.finallyInsertLine).orElse(null);
                if (targetTry == null)
                    continue; // nothing to do for this leak

                applyTryFinally(cu, owner, info, targetTry);
            }

            java.nio.file.Files.write(src,
                    com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter.print(cu).getBytes());
            java.util.List<String> patched = CompilerUtils.compile(projectRoot);

            if (!CompilerUtils.outputsDiffer(baselineOutput, patched)) {
                java.nio.file.Path backup = java.nio.file.Files.createTempFile("orig-", ".java");
                java.nio.file.Files.write(backup, original);
                java.util.List<String> diff = PatchUtils.diff(backup.toString(), src.toString());
                PatchUtils.writePatch(java.nio.file.Paths.get("rlfixer.patch"), diff);
                java.nio.file.Files.copy(backup, src, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                java.nio.file.Files.deleteIfExists(backup);
                return true;
            } else {
                // Final revert; overall failure
                java.nio.file.Files.write(src, original);
                return false;
            }
        }
    }

    // Finds the smallest TryStmt that spans cfLeakLine; if none, spans
    // finallyInsertLine.
    private static Optional<TryStmt> findTargetTryStmt(CallableDeclaration<?> cd,
            int cfLeakLine,
            int finallyInsertLine) {
        // helper: pick the smallest (by line span)
        java.util.Comparator<TryStmt> bySpan = (a, b) -> Integer.compare(
                a.getEnd().get().line - a.getBegin().get().line,
                b.getEnd().get().line - b.getBegin().get().line);

        // Pass 1: try containing the CF leak line
        if (cfLeakLine > 0) {
            Optional<TryStmt> byLeak = cd.findAll(TryStmt.class).stream()
                    .filter(ts -> ts.getBegin().isPresent() && ts.getEnd().isPresent())
                    .filter(ts -> {
                        int begin = ts.getBegin().get().line;
                        int end = ts.getEnd().get().line;
                        return begin <= cfLeakLine && cfLeakLine <= end;
                    })
                    .min(bySpan);
            if (byLeak.isPresent())
                return byLeak;
        }

        // Pass 2: try containing the finally insert line
        if (finallyInsertLine > 0) {
            Optional<TryStmt> byFinally = cd.findAll(TryStmt.class).stream()
                    .filter(ts -> ts.getBegin().isPresent() && ts.getEnd().isPresent())
                    .filter(ts -> {
                        int begin = ts.getBegin().get().line;
                        int end = ts.getEnd().get().line;
                        return begin <= finallyInsertLine && finallyInsertLine <= end;
                    })
                    .min(bySpan);
            if (byFinally.isPresent())
                return byFinally;
        }

        return Optional.empty();
    }

    private static boolean applyTryWithResources(CompilationUnit cu,
            CallableDeclaration method,
            PromptInfo info,
            TryStmt targetTry) {
        // 1. Determine if allocation is already stored in a variable
        Optional<String> varNameOpt = extractLeakedVariableName(cu, info.cfLeakLine, info.allocationExprText);
        VariableDeclarationExpr resourceDecl;
        String resourceVarName;

        if (varNameOpt.isPresent()) {
            resourceVarName = varNameOpt.get();
            VariableDeclarator vd = method.findAll(VariableDeclarator.class).stream()
                    .filter(v -> v.getNameAsString().equals(resourceVarName))
                    .findFirst().orElse(null);
            if (vd == null)
                return false;
            resourceDecl = (VariableDeclarationExpr) vd.getParentNode().get();
        } else {
            // nested allocation – introduce temp var
            resourceVarName = "__arodnap_temp" + info.index;
            String decl = info.resourceType + " " + resourceVarName + " = " + info.allocationExprText + ";";
            BlockStmt blk = StaticJavaParser.parseBlock("{ " + decl + " }");
            resourceDecl = blk.getStatement(0).asExpressionStmt().getExpression().asVariableDeclarationExpr();
        }

        // 3. Remove original declaration if present
        if (varNameOpt.isPresent()) {
            Statement declStmt = getEnclosingStmt(resourceDecl);
            if (declStmt != null)
                declStmt.remove();
        }

        // 4. For nested case, replace allocation expr with NameExpr(temp)
        if (varNameOpt.isEmpty()) {
            NameExpr tempRef = new NameExpr(resourceVarName);

            targetTry.findAll(Expression.class).forEach(expr -> {
                if (expr.toString().replace(" ", "").equals(info.allocationExprText.replace(" ", ""))) {
                    expr.replace(tempRef.clone());
                }
            });
        }

        // 5. Attach resource to try‑with‑resources header
        NodeList<Expression> res = new NodeList<>();
        res.add(resourceDecl.clone());
        targetTry.setResources(res);
        return true;
    }

    private static boolean applyTryFinally(CompilationUnit cu, CallableDeclaration method, PromptInfo info,
            TryStmt targetTry) {
        // Optional<TryStmt> tryOpt = findTargetTryStmt(method, info.cfLeakLine,
        // info.finallyInsertLine);
        // if (tryOpt.isEmpty())
        // return false;
        // TryStmt targetTry = tryOpt.get();

        /* 1 ─ detect or create variable */
        Optional<String> varOpt = extractLeakedVariableName(cu, info.cfLeakLine, info.allocationExprText);
        String varName = varOpt.orElse("__arodnap_temp" + info.index);

        BlockStmt parentBlock = (BlockStmt) targetTry.getParentNode().orElse(null);
        if (parentBlock == null)
            return false;

        if (varOpt.isPresent()) {
            // 2a ─ move existing declaration outside
            VariableDeclarator vd = method.findAll(VariableDeclarator.class).stream()
                    .filter(v -> v.getNameAsString().equals(varName))
                    .findFirst().orElse(null);
            if (vd == null)
                return false;

            Statement declStmt = getEnclosingStmt(vd.getParentNode().get());
            if (declStmt == null)
                return false;

            // Is the declaration inside the same try?
            boolean declaredInsideTry = declStmt.findAncestor(TryStmt.class)
                    .map(ts -> ts == targetTry)
                    .orElse(false);

            if (declaredInsideTry) {
                // Hoist: insert "Type v = null;" before try, and turn the decl into an
                // assignment
                String nullDecl = info.resourceType + " " + varName + " = null;";
                int tryIndex = parentBlock.getStatements().indexOf(targetTry);
                parentBlock.addStatement(tryIndex, StaticJavaParser.parseStatement(nullDecl));

                // Use the ORIGINAL initializer as RHS
                Expression initExpr = vd.getInitializer().map(Expression::clone).orElse(null);
                if (initExpr != null) {

                    VariableDeclarationExpr vde = (VariableDeclarationExpr) vd.getParentNode().get();
                    if (vde.getVariables().size() == 1) {
                        declStmt.replace(new ExpressionStmt(
                                new AssignExpr(new NameExpr(varName), initExpr, AssignExpr.Operator.ASSIGN)));
                    } else {
                        vd.setInitializer((Expression) null);
                        BlockStmt block = (BlockStmt) declStmt.getParentNode().get();
                        int idx = block.getStatements().indexOf(declStmt);
                        block.addStatement(idx + 1, new ExpressionStmt(
                                new AssignExpr(new NameExpr(varName), initExpr, AssignExpr.Operator.ASSIGN)));
                    }
                } else {
                    // No intializer, meaning variable was declared without an initializer
                    // e.g., "FileInputStream fis;" and the intialization is done later
                    // in the try block.
                    // In this case we'll keep the initialization as is, but remove the declaration
                    // from the try block as it is now hoisted.
                    declStmt.remove();
                }
            } else {
                // Already declared outside: DO NOT re-declare or hoist.
                // Ensure it's initialized to null so 'if (v != null)' in finally compiles.
                if (!vd.getInitializer().isPresent()) {
                    vd.setInitializer(new com.github.javaparser.ast.expr.NullLiteralExpr());
                }
            }

        } else {
            // 2b ─ introduce temp var declaration BEFORE the try stmt
            String nullDecl = info.resourceType + " " + varName + " = null;";
            int tryIdx = parentBlock.getStatements().indexOf(targetTry);
            parentBlock.addStatement(tryIdx, StaticJavaParser.parseStatement(nullDecl));

            // 1. exact Expression node
            Expression allocExpr = targetTry.getTryBlock().findFirst(Expression.class,
                    e -> e.toString().replace(" ", "").equals(info.allocationExprText.replace(" ", ""))).orElse(null);
            if (allocExpr == null)
                return false;

            // 2. climb to the statement whose parent is the try's BlockStmt
            Node n = allocExpr;
            Statement allocationStmt = null;
            BlockStmt tryBody = targetTry.getTryBlock();

            while (n != null) {
                if (n instanceof Statement && n.getParentNode().orElse(null) == tryBody) {
                    allocationStmt = (Statement) n;
                    break;
                }
                n = n.getParentNode().orElse(null);
            }
            if (allocationStmt == null)
                return false;

            /* insert “temp = allocationExpr;” right BEFORE that statement */
            String assignSrc = varName + " = " + info.allocationExprText + ";";
            BlockStmt tryBlock = targetTry.getTryBlock();
            int idx = tryBlock.getStatements().indexOf(allocationStmt);
            tryBlock.addStatement(idx, StaticJavaParser.parseStatement(assignSrc));

            /* replace the allocation expression in that original statement */
            // replace the nested expression inside that one statement
            allocExpr.replace(new NameExpr(varName));
        }

        /* 3 ─ finally block with null-check */
        String args = (info.finalizerDefaultArgs == null || info.finalizerDefaultArgs.isEmpty())
                ? ""
                : String.join(", ", info.finalizerDefaultArgs);
        String finallySrc = String.join("",
                "{",
                "  if (" + varName + " != null) {",
                "    try { " + varName + "." + info.finalizerMethod + "(", args, "); }",
                "    catch (Exception e) { e.printStackTrace(); }",
                "  }",
                "}");
        if (targetTry.getFinallyBlock().isPresent()) {
            // append to existing finally block
            BlockStmt fb = targetTry.getFinallyBlock().get();
            fb.getStatements().addAll(StaticJavaParser.parseBlock(finallySrc).getStatements());
        } else {
            // create new finally block
            targetTry.setFinallyBlock(StaticJavaParser.parseBlock(finallySrc));

        }
        return true;
    }

    public static Optional<String> extractLeakedVariableName(CompilationUnit cu,
            int line,
            String allocationExprText) {
        // Case 1: CF reported a variable name (identifier), e.g., "stream"
        if (allocationExprText != null && allocationExprText.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            String ident = allocationExprText;

            // Variable declaration with that name, whose enclosing statement spans the leak
            // line
            Optional<VariableDeclarator> byNameDecl = cu.findAll(VariableDeclarator.class).stream()
                    .filter(v -> v.getNameAsString().equals(ident))
                    .filter(v -> {
                        Optional<Statement> st = v.findAncestor(Statement.class);
                        if (st.isPresent() && st.get().getBegin().isPresent() && st.get().getEnd().isPresent()) {
                            int b = st.get().getBegin().get().line;
                            int e = st.get().getEnd().get().line;
                            return b <= line && line <= e;
                        }
                        return v.getBegin().isPresent() && v.getBegin().get().line == line;
                    })
                    .findFirst();
            if (byNameDecl.isPresent())
                return Optional.of(ident);

            // Assignment to that variable on the leak line/span
            Optional<AssignExpr> byNameAssign = cu.findAll(AssignExpr.class).stream()
                    .filter(a -> a.getTarget().isNameExpr()
                            && a.getTarget().asNameExpr().getNameAsString().equals(ident))
                    .filter(a -> {
                        Optional<Statement> st = a.findAncestor(Statement.class);
                        if (st.isPresent() && st.get().getBegin().isPresent() && st.get().getEnd().isPresent()) {
                            int b = st.get().getBegin().get().line;
                            int e = st.get().getEnd().get().line;
                            return b <= line && line <= e;
                        }
                        return a.getBegin().isPresent() && a.getBegin().get().line == line;
                    })
                    .findFirst();
            if (byNameAssign.isPresent())
                return Optional.of(ident);
        }
        // Case 2: original behavior — match initializer/value text (e.g., "new
        // Foo(...)" or "conn.getX()")
        Optional<VariableDeclarator> directMatch = cu.findAll(VariableDeclarator.class).stream()
                .filter(v -> v.getBegin().isPresent() && v.getBegin().get().line == line)
                .filter(v -> v.getInitializer().isPresent())
                .filter(v -> v.getInitializer().get().toString().replace(" ", "")
                        .equals(allocationExprText.replace(" ", "")))
                .findFirst();

        if (directMatch.isPresent()) {
            return Optional.of(directMatch.get().getNameAsString());
        }

        Optional<AssignExpr> assignExpr = cu.findAll(AssignExpr.class).stream()
                .filter(a -> a.getBegin().isPresent() && a.getBegin().get().line == line)
                .filter(a -> a.getValue().toString().replace(" ", "").equals(allocationExprText.replace(" ", "")))
                .filter(a -> a.getTarget().isNameExpr())
                .findFirst();

        if (assignExpr.isPresent()) {
            return Optional.of(assignExpr.get().getTarget().asNameExpr().getNameAsString());
        }

        return Optional.empty();
    }

    private static Statement getEnclosingStmt(Node n) {
        while (n != null && !(n instanceof Statement)) {
            n = n.getParentNode().orElse(null);
        }
        return (Statement) n;
    }
}
