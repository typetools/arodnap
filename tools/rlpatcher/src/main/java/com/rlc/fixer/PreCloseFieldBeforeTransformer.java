package com.rlc.fixer;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Inserts a pre-close guard for non-final owning fields:
 *
 * if (this.<field> != null) {
 * try { this.<field>.<finalizer>(args...); }
 * catch (Exception|Throwable e) { e.printStackTrace(); }
 * }
 *
 * Uses an anchor Statement to avoid index shifting when multiple insertions
 * target the same BlockStmt.
 */
public class PreCloseFieldBeforeTransformer {

    /** Main entry: apply all PRE_CLOSE_FIELD_BEFORE fixes in a single pass. */
    public static boolean apply(List<PromptInfo> infos,
            String projectRoot,
            List<String> baselineOutput)
            throws IOException, InterruptedException {

        if (infos == null || infos.isEmpty())
            return false;

        // Filter relevant prompts (defensive)
        List<PromptInfo> work = infos.stream()
                .filter(pi -> pi.patchType == PatchType.PRE_CLOSE_FIELD_BEFORE)
                .collect(Collectors.toList());
        if (work.isEmpty())
            return false;

        // Sort by insertion line, earliest first (more natural ordering)
        work.sort(Comparator.comparingInt(PreCloseFieldBeforeTransformer::effectiveInsertLine));

        // All infos are for the same file (per your grouping). Use the first path.
        Path src = Paths.get(work.get(0).leakSourceFile);
        List<String> original = Files.readAllLines(src);

        // Parse with lexical preservation
        CompilationUnit cu = LexicalPreservingPrinter.setup(StaticJavaParser.parse(src));

        // Build plans (anchor-based)
        List<Plan> plans = new ArrayList<>();
        for (PromptInfo info : work) {
            Optional<CallableDeclaration> ownerOpt = cu.findFirst(
                    CallableDeclaration.class,
                    m -> beginLine(m) <= effectiveInsertLine(info)
                            && effectiveInsertLine(info) <= endLine(m));
            if (ownerOpt.isEmpty())
                continue;
            CallableDeclaration<?> owner = ownerOpt.get();

            Optional<BlockStmt> containerOpt = smallestContainer(owner, effectiveInsertLine(info));
            if (containerOpt.isEmpty())
                continue;
            BlockStmt container = containerOpt.get();

            Statement anchor = firstTopLevelStmtAtOrAfter(container, effectiveInsertLine(info));
            plans.add(new Plan(info, container, anchor));
        }

        if (plans.isEmpty())
            return false;

        // Apply per-container, recomputing indices from the live anchor
        Map<BlockStmt, List<Plan>> byContainer = plans.stream().collect(Collectors.groupingBy(p -> p.container));

        for (Map.Entry<BlockStmt, List<Plan>> e : byContainer.entrySet()) {
            BlockStmt container = e.getKey();
            List<Plan> group = e.getValue();
            // Stable and deterministic within this container
            group.sort(Comparator.comparingInt(p -> effectiveInsertLine(p.info)));

            for (Plan p : group) {
                Statement node = buildCloseGuard(p.info);
                if (node == null)
                    continue;

                NodeList<Statement> stmts = container.getStatements();
                int idx = computeLiveIndex(container, p.anchor, effectiveInsertLine(p.info));

                // very small de-duplication: if identical node already sits just above
                if (idx > 0 && equalsNoSpace(stmts.get(idx - 1).toString(), node.toString())) {
                    continue;
                }
                stmts.add(idx, node);
            }
        }

        // Write, compile, compare to baseline
        Files.write(src, LexicalPreservingPrinter.print(cu).getBytes());
        List<String> patched = CompilerUtils.compile(projectRoot);

        if (!CompilerUtils.outputsDiffer(baselineOutput, patched)) {
            // Emit patch & restore original file
            Path backup = Files.createTempFile("orig-", ".java");
            Files.write(backup, original);
            List<String> diff = PatchUtils.diff(backup.toString(), src.toString());
            PatchUtils.writePatch(Paths.get("rlfixer.patch"), diff);
            Files.copy(backup, src, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(backup);
            return true;
        } else {
            Files.write(src, original);
            return false;
        }
    }

    // ------------------------- planning -------------------------

    /** Plan anchored to a specific statement; we insert BEFORE this statement. */
    private static final class Plan {
        final PromptInfo info;
        final BlockStmt container;
        final Statement anchor; // may be null => append to end

        Plan(PromptInfo info, BlockStmt container, Statement anchor) {
            this.info = info;
            this.container = container;
            this.anchor = anchor;
        }
    }

    /** Smallest BlockStmt that covers the line. */
    private static Optional<BlockStmt> smallestContainer(CallableDeclaration<?> owner, int line) {
        return owner.findAll(BlockStmt.class).stream()
                .filter(b -> beginLine(b) <= line && line <= endLine(b))
                .min(Comparator.comparingInt(b -> endLine(b) - beginLine(b)));
    }

    /**
     * First top-level statement in the container whose begin line >= insertLine.
     */
    private static Statement firstTopLevelStmtAtOrAfter(BlockStmt container, int insertLine) {
        for (Statement s : container.getStatements()) {
            if (!s.getRange().isPresent())
                continue;
            if (s.getRange().get().begin.line >= insertLine)
                return s;
        }
        return null; // append case
    }

    /**
     * Compute the current index for insertion using the live anchor, fallback to
     * line scan.
     */
    private static int computeLiveIndex(BlockStmt container, Statement anchor, int insertLine) {
        NodeList<Statement> stmts = container.getStatements();
        if (anchor != null && anchor.getParentNode().orElse(null) == container) {
            int i = stmts.indexOf(anchor);
            if (i >= 0)
                return i;
        }
        // fallback: find first statement whose begin >= insertLine
        for (int i = 0; i < stmts.size(); i++) {
            if (!stmts.get(i).getRange().isPresent())
                continue;
            if (stmts.get(i).getRange().get().begin.line >= insertLine)
                return i;
        }
        return stmts.size(); // append
    }

    // ------------------------- snippet builder -------------------------

    /**
     * Build the "if (this.field != null) { try { this.field.finalizer(args); }
     * catch (...) {} }" statement.
     */
    private static Statement buildCloseGuard(PromptInfo info) {
        String field = (info.owningFieldName != null && !info.owningFieldName.isEmpty())
                ? info.owningFieldName
                : null;
        if (field == null)
            return null; // nothing to do if we don't know the field

        String qualifier = "this." + field;

        String fin = (info.finalizerMethod == null || info.finalizerMethod.isEmpty())
                ? "close"
                : info.finalizerMethod;

        String args = (info.finalizerDefaultArgs == null || info.finalizerDefaultArgs.isEmpty())
                ? ""
                : String.join(", ", info.finalizerDefaultArgs);

        // finalize can throw Throwable; default to Exception otherwise
        String catchType = "finalize".equals(fin) ? "Throwable" : "Exception";

        String snippet = String.join("",
                "if (", qualifier, " != null) {",
                "  try { ", qualifier, ".", fin, "(", args, "); }",
                "  catch (", catchType, " e) { e.printStackTrace(); }",
                "}");

        return StaticJavaParser.parseStatement(snippet);
    }

    // ------------------------- utilities -------------------------

    private static int effectiveInsertLine(PromptInfo info) {
        // Prefer explicit "before" line parsed from RLFixer hint; fall back sensibly.
        if (info.preInsertBeforeLine > 0)
            return info.preInsertBeforeLine;
        if (info.fixLeakLine > 0)
            return info.fixLeakLine;
        return Math.max(info.cfLeakLine, 1);
    }

    private static int beginLine(Node n) {
        return n.getRange().map(r -> r.begin.line).orElse(Integer.MAX_VALUE);
    }

    private static int endLine(Node n) {
        return n.getRange().map(r -> r.end.line).orElse(Integer.MIN_VALUE);
    }

    private static boolean equalsNoSpace(String a, String b) {
        if (a == null || b == null)
            return false;
        return a.replace(" ", "").equals(b.replace(" ", ""));
    }
}
