package com.rlc.fixer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class PreCloseFieldBeforeTransformerTest {

    @TempDir
    Path tempDir;
    Path projectRoot, srcFile;
    List<String> originalSource, baseline;

    // ---------- Single-leak case ----------
    @Test
    void preClose_singleLeak_insertsGuardBeforeAssignment() throws Exception {
        projectRoot = tempDir.resolve("proj1");
        srcFile = projectRoot.resolve("src/demo/Svc.java");
        Files.createDirectories(srcFile.getParent());

        String code = String.join("\n",
                "package demo;",
                "",
                "class Res { void close() {} }",
                "",
                "public class Svc {",
                "  private Res map;",
                "",
                "  public void make() {",
                "    map = new Res(); // INSERT-BEFORE",
                "    // use map",
                "  }",
                "}");
        Files.writeString(srcFile, code);
        originalSource = Files.readAllLines(srcFile);

        // compile baseline
        baseline = CompilerUtils.compile(projectRoot.toString());

        // Find the line to insert before
        int insertBefore = findLine(srcFile, "INSERT-BEFORE");
        assertTrue(insertBefore > 0, "insert line must be found");

        // Build PromptInfo
        PromptInfo p = new PromptInfo();
        p.patchType = PatchType.PRE_CLOSE_FIELD_BEFORE;
        p.leakSourceFile = srcFile.toString();
        p.cfLeakLine = insertBefore; // not strictly required, but set
        p.fixLeakLine = insertBefore; // fallback
        p.preInsertBeforeLine = insertBefore; // primary control
        p.owningFieldName = "map";
        p.finalizerMethod = "close";
        p.finalizerDefaultArgs = Collections.emptyList();

        boolean ok = PreCloseFieldBeforeTransformer.apply(
                Collections.singletonList(p),
                projectRoot.toString(),
                baseline);
        assertTrue(ok, "patch should succeed");

        Path patch = Paths.get("rlfixer.patch");
        assertTrue(Files.exists(patch), "patch file should exist");

        String diff = Files.readString(patch);
        assertTrue(diff.contains("if (this.map != null)"), "guard should be inserted");
        assertTrue(diff.contains("this.map.close()"), "close should be called");

        // original restored
        assertEquals(originalSource, Files.readAllLines(srcFile));
    }

    // ---------- Multi-leak case (two fields) ----------
    @Test
    void preClose_multiLeak_twoGuards_anchorBased() throws Exception {
        projectRoot = tempDir.resolve("proj2");
        srcFile = projectRoot.resolve("src/demo/Svc2.java");
        Files.createDirectories(srcFile.getParent());

        String code = String.join("\n",
                "package demo;",
                "",
                "class Res { void close() {} }",
                "",
                "public class Svc2 {",
                "  private Res a;",
                "  private Res b;",
                "",
                "  public void makeBoth() {",
                "    a = new Res(); // A-INSERT",
                "    // some code",
                "    b = new Res(); // B-INSERT",
                "  }",
                "}");
        Files.writeString(srcFile, code);
        originalSource = Files.readAllLines(srcFile);

        // compile baseline
        baseline = CompilerUtils.compile(projectRoot.toString());

        int insertA = findLine(srcFile, "A-INSERT");
        int insertB = findLine(srcFile, "B-INSERT");
        assertTrue(insertA > 0 && insertB > 0, "both insert lines must be found");

        PromptInfo pA = new PromptInfo();
        pA.patchType = PatchType.PRE_CLOSE_FIELD_BEFORE;
        pA.leakSourceFile = srcFile.toString();
        pA.preInsertBeforeLine = insertA;
        pA.cfLeakLine = insertA;
        pA.fixLeakLine = insertA;
        pA.owningFieldName = "a";
        pA.finalizerMethod = "close";
        pA.finalizerDefaultArgs = Collections.emptyList();

        PromptInfo pB = new PromptInfo();
        pB.patchType = PatchType.PRE_CLOSE_FIELD_BEFORE;
        pB.leakSourceFile = srcFile.toString();
        pB.preInsertBeforeLine = insertB;
        pB.cfLeakLine = insertB;
        pB.fixLeakLine = insertB;
        pB.owningFieldName = "b";
        pB.finalizerMethod = "close";
        pB.finalizerDefaultArgs = Collections.emptyList();

        boolean ok = PreCloseFieldBeforeTransformer.apply(
                Arrays.asList(pA, pB),
                projectRoot.toString(),
                baseline);
        assertTrue(ok, "patch should succeed for two guards");

        Path patch = Paths.get("rlfixer.patch");
        assertTrue(Files.exists(patch), "patch file should exist");

        String diff = Files.readString(patch);
        assertTrue(diff.contains("if (this.a != null)"), "guard for a should be present");
        assertTrue(diff.contains("this.a.close()"), "close for a should be called");
        assertTrue(diff.contains("if (this.b != null)"), "guard for b should be present");
        assertTrue(diff.contains("this.b.close()"), "close for b should be called");

        // original restored
        assertEquals(originalSource, Files.readAllLines(srcFile));
    }

    // ---------- helper ----------
    private static int findLine(Path file, String marker) throws Exception {
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(marker))
                return i + 1; // 1-based
        }
        return -1;
    }
}
