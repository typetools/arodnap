package com.rlc.fixer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class OnlyFinallyAlreadyDeclaredVarTest {

    @TempDir Path tempDir;
    Path projectRoot, srcFile;
    List<String> originalSource, baseline;

    @BeforeEach
    void setup() throws Exception {
        projectRoot = tempDir.resolve("proj");
        srcFile = projectRoot.resolve("src/demo/BaseTest.java");
        Files.createDirectories(srcFile.getParent());

        // Minimal repro: var declared OUTSIDE the try; assignment INSIDE the try.
        String code = String.join("\n",
            "package demo;",
            "public class BaseTest {",
            "  public void testEdgeWeights(java.io.File counts) {",
            "    java.io.FileInputStream fstream;",                              // decl outside try
            "    try {",
            "      fstream = new java.io.FileInputStream(counts);",             // assignment (leak line)",
            "      java.io.DataInputStream in = new java.io.DataInputStream(fstream);",
            "      java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(in));",
            "      String strLine = br.readLine();",
            "    } catch (Exception e) {",
            "      e.printStackTrace();",
            "    }",
            "  }",
            "}"
        );
        Files.writeString(srcFile, code);
        originalSource = Files.readAllLines(srcFile);

        // compile baseline
        baseline = CompilerUtils.compile(projectRoot.toString());

        // delete patch file if present
        Path patch = Paths.get("rlfixer.patch");
        if (Files.exists(patch))
            Files.delete(patch);
    }

    @Test
    void onlyFinally_whenVarDeclaredOutsideTry() throws Exception {
        // find leak line (assignment)
        int leakLine = 0;
        List<String> lines = Files.readAllLines(srcFile);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("new java.io.FileInputStream(counts)")) {
                leakLine = i + 1; // 1-based
                break;
            }
        }
        assertTrue(leakLine > 0, "leak line found");

        // Build PromptInfo like CF/RLFixer
        PromptInfo p = new PromptInfo();
        p.leakSourceFile      = srcFile.toString();
        p.rlfixerSourceFile   = srcFile.toString();
        p.resourceType        = "java.io.FileInputStream";
        p.allocationExprText  = "fstream";                 // CF reported the variable name
        p.finalizerMethod     = "close";                   // default, but set explicitly
        p.cfLeakLine          = leakLine;
        p.finallyInsertLine   = leakLine + 4;              // within/after try body
        p.patchType           = PatchType.ONLY_FINALLY;

        boolean ok = OnlyFinallyTransformer.apply(Collections.singletonList(p), projectRoot.toString(), baseline);
        assertTrue(ok, "patch should succeed (no redeclaration/compile errors)");

        // patch file exists and mentions finally + close
        Path patch = Paths.get("rlfixer.patch");
        assertTrue(Files.exists(patch));
        String diff = Files.readString(patch);
        assertTrue(diff.contains("finally"), "diff adds finally");
        assertTrue(diff.contains(".close()"), "diff closes the resource");

        // file restored after emitting patch
        assertEquals(originalSource, Files.readAllLines(srcFile));
    }
}
