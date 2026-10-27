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
            SourceText text = new SourceText(java.nio.file.Files.readString(src));

            // Pre-resolve target try statements in a list parallel to infos
            // java.util.List<com.github.javaparser.ast.stmt.TryStmt> tryList =
            // resolveTryStmts(cu, infos);

            // Apply TWR edits using pre-bound try nodes. If any leak cannot be converted
            // without reordering code, go straight to the try-finally form.
            boolean allConverted = true;
            for (int i = 0; i < infos.size() && allConverted; i++) {
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
                allConverted = applyTryWithResources(cu, owner, info, targetTry, text);
            }

            // Write, compile, check against baseline
            java.util.List<String> patched = null;
            if (allConverted) {
                String rendered = text.result().orElseGet(
                        () -> com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter.print(cu));
                java.nio.file.Files.write(src, rendered.getBytes());
                patched = CompilerUtils.compile(projectRoot);
            }

            if (allConverted && !CompilerUtils.outputsDiffer(baselineOutput, patched)) {
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
            SourceText text = new SourceText(java.nio.file.Files.readString(src));

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

                if (!applyTryFinally(cu, owner, info, targetTry, text)) {
                    throw new UnsafeEditException("could not place a close() for the leak at line "
                            + info.cfLeakLine + " without changing what the code does");
                }
            }

            // Prefer the text rendering (see SourceText); the AST printer is the fallback.
            String rendered = text.result().orElseGet(
                    () -> com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter.print(cu));
            java.nio.file.Files.write(src, rendered.getBytes());
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
            TryStmt targetTry,
            SourceText text) {
        // The resource header runs before the try body. It may only take over code that
        // already ran first in the body; see MoveSafety.
        NodeList<Statement> body = targetTry.getTryBlock().getStatements();
        if (body.isEmpty())
            return false;
        Statement firstStmt = body.get(0);

        // 1. Determine if allocation is already stored in a variable
        Optional<String> varNameOpt = extractLeakedVariableName(cu, info.cfLeakLine, info.allocationExprText);
        VariableDeclarationExpr resourceDecl;
        String resourceVarName;
        Expression allocation = null;

        if (varNameOpt.isPresent()) {
            resourceVarName = varNameOpt.get();
            // Only a single-variable declaration that is the body's first statement can move.
            if (!firstStmt.isExpressionStmt()
                    || !firstStmt.asExpressionStmt().getExpression().isVariableDeclarationExpr())
                return false;
            resourceDecl = firstStmt.asExpressionStmt().getExpression().asVariableDeclarationExpr();
            if (resourceDecl.getVariables().size() != 1
                    || !resourceDecl.getVariable(0).getNameAsString().equals(resourceVarName)
                    || resourceDecl.getVariable(0).getInitializer().isEmpty())
                return false;
        } else {
            if (info.resourceType == null)
                return false;
            // A wrapper declared in the header, "try (W w = new W(open()))": the allocation
            // becomes a resource of its own just before it.
            NodeList<Expression> resources = targetTry.getResources();
            for (int k = 0; k < resources.size(); k++) {
                List<Expression> inResource = Allocations.find(resources.get(k), info);
                if (inResource.size() == 1)
                    return addResourceBefore(targetTry, k, inResource.get(0), info, text);
            }
            List<Expression> matches = Allocations.find(targetTry.getTryBlock(), info);
            if (matches.size() != 1
                    || !MoveSafety.isFirstEffectOf(matches.get(0), firstStmt))
                return false;
            allocation = matches.get(0);
            // nested allocation – introduce temp var
            resourceVarName = "__arodnap_temp" + info.index;
            resourceDecl = new VariableDeclarationExpr(new VariableDeclarator(
                    StaticJavaParser.parseClassOrInterfaceType(TypeNames.inFile(cu, info.resourceType)), resourceVarName,
                    allocation.clone()));
        }

        WindowRendering.renderResourceAdded(text, targetTry,
                allocation == null ? text.text(resourceDecl.getRange().get()) : resourceDecl.toString(),
                allocation == null ? firstStmt : null, allocation, resourceVarName);

        // 3. Remove original declaration if present
        if (varNameOpt.isPresent()) {
            Statement declStmt = getEnclosingStmt(resourceDecl);
            if (declStmt != null)
                declStmt.remove();
        }

        // 4. For nested case, replace allocation expr with NameExpr(temp)
        if (varNameOpt.isEmpty()) {
            allocation.replace(new NameExpr(resourceVarName));
        }

        // 5. Attach resource to try‑with‑resources header, after any existing resources
        // (those are initialized before the body, so the order is unchanged)
        NodeList<Expression> res = new NodeList<>(targetTry.getResources());
        res.add(resourceDecl.clone());
        targetTry.setResources(res);
        return true;
    }

    private static boolean addResourceBefore(TryStmt targetTry, int index, Expression allocation, PromptInfo info,
            SourceText text) {
        Expression resource = targetTry.getResources().get(index);
        if (!MoveSafety.isFirstEffectOf(allocation, resource))
            return false;
        String name = "__arodnap_temp" + info.index;
        VariableDeclarationExpr decl = new VariableDeclarationExpr(new VariableDeclarator(
                StaticJavaParser.parseClassOrInterfaceType(TypeNames.inFile(
                        targetTry.findCompilationUnit().orElseThrow(), info.resourceType)), name, allocation.clone()));
        WindowRendering.renderResourceInserted(text, resource, allocation, decl.toString(), name);
        allocation.replace(new NameExpr(name));
        NodeList<Expression> resources = new NodeList<>(targetTry.getResources());
        resources.add(index, decl);
        targetTry.setResources(resources);
        return true;
    }

    private static boolean applyTryFinally(CompilationUnit cu, CallableDeclaration method, PromptInfo info,
            TryStmt targetTry, SourceText text) {
        WindowRendering.FinallyEdits edits = new WindowRendering.FinallyEdits();
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
                // assignment. The declared type keeps later uses of the variable unchanged.
                String type = vd.getType().toString();
                String nullDecl = (type.equals("var") ? TypeNames.inFile(cu, info.resourceType) : type) + " " + varName + " = null;";
                edits.nullDeclaration = nullDecl;
                int tryIndex = parentBlock.getStatements().indexOf(targetTry);
                parentBlock.addStatement(tryIndex, StaticJavaParser.parseStatement(nullDecl));

                // Use the ORIGINAL initializer as RHS
                Expression initExpr = vd.getInitializer().map(Expression::clone).orElse(null);
                if (initExpr != null) {

                    VariableDeclarationExpr vde = (VariableDeclarationExpr) vd.getParentNode().get();
                    if (vde.getVariables().size() == 1) {
                        edits.declarationToAssignment = declStmt;
                        edits.variableName = vd.getName();
                        declStmt.replace(new ExpressionStmt(
                                new AssignExpr(new NameExpr(varName), initExpr, AssignExpr.Operator.ASSIGN)));
                    } else {
                        text.giveUp();
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
                    edits.declarationToRemove = declStmt;
                    declStmt.remove();
                }
            } else {
                // Already declared outside: DO NOT re-declare or hoist.
                // Ensure it's initialized to null so 'if (v != null)' in finally compiles.
                if (!vd.getInitializer().isPresent()) {
                    edits.nameToInitialize = vd.getName();
                    vd.setInitializer(new com.github.javaparser.ast.expr.NullLiteralExpr());
                }
            }

        } else {
            // 1. exact Expression node
            List<Expression> matches = Allocations.find(targetTry.getTryBlock(), info);
            if (info.resourceType == null || matches.isEmpty())
                return false;
            Expression allocExpr = matches.get(0);

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
            // "temp = allocationExpr;" runs before the rest of that statement
            if (!MoveSafety.isFirstEffectOf(allocExpr, allocationStmt))
                return false;

            // 2b ─ introduce temp var declaration BEFORE the try stmt
            String nullDecl = TypeNames.inFile(cu, info.resourceType) + " " + varName + " = null;";
            edits.nullDeclaration = nullDecl;
            edits.allocation = allocExpr;
            edits.allocationStatement = allocationStmt;
            int tryIdx = parentBlock.getStatements().indexOf(targetTry);
            parentBlock.addStatement(tryIdx, StaticJavaParser.parseStatement(nullDecl));

            /* insert “temp = allocationExpr;” right BEFORE that statement */
            BlockStmt tryBlock = targetTry.getTryBlock();
            int idx = tryBlock.getStatements().indexOf(allocationStmt);
            tryBlock.addStatement(idx, new ExpressionStmt(
                    new AssignExpr(new NameExpr(varName), allocExpr.clone(), AssignExpr.Operator.ASSIGN)));

            /* replace the allocation expression in that original statement */
            // replace the nested expression inside that one statement
            allocExpr.replace(new NameExpr(varName));
        }

        /* 3 ─ finally block with null-check */
        String args = (info.finalizerDefaultArgs == null || info.finalizerDefaultArgs.isEmpty())
                ? ""
                : String.join(", ", info.finalizerDefaultArgs);
        WindowRendering.renderAddedFinally(text, targetTry, varName, info.finalizerMethod + "(" + args + ")",
                "Exception", edits);
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
        if (allocationExprText == null)
            return Optional.empty();
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
