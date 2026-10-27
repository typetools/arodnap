package com.rlc.fixer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OnlyFinallyDirectVarTest {

    @TempDir Path tempDir;
    Path srcFile;
    List<String> originalSource;
    List<String> baselineCompile;

    @BeforeEach
    void setup() throws Exception {
        // Synthetic file with direct variable declaration
        String code = String.join("\n",
            "package demo;",
            "import java.net.Socket;",
            "public class Demo2 {",
            "  public static void main(String[] a) {",
            "    try {",
            "      Socket sock = new Socket(\"localhost\", 9090);",   // leak line → 6
            "      System.out.println(sock);",
            "    } catch (Exception e) {",
            "      e.printStackTrace();",
            "    }",
            "  }",
            "}"
        );

        Path projectRoot = tempDir.resolve("proj");
        srcFile = projectRoot.resolve("src/demo/Demo2.java");
        Files.createDirectories(srcFile.getParent());
        Files.write(srcFile, code.getBytes());

        originalSource   = Files.readAllLines(srcFile);
        baselineCompile  = CompilerUtils.compile(projectRoot.toString());

        // delete patch file if present
        Path patch = Paths.get("rlfixer.patch");
        if (Files.exists(patch))
            Files.delete(patch);
    }

    @Test
    void directVariableTwrPatch() throws Exception {
        PromptInfo p = new PromptInfo();
        p.leakSourceFile      = srcFile.toString();
        p.rlfixerSourceFile   = srcFile.toString();
        p.resourceType        = "java.net.Socket";
        p.allocationExprText  = "new Socket(\"localhost\", 9090)";
        p.cfLeakLine          = 6;
        p.finallyInsertLine   = 10;                // below catch line
        p.patchType           = PatchType.ONLY_FINALLY;

        boolean ok = OnlyFinallyTransformer.apply(
                Collections.singletonList(p), tempDir.resolve("proj").toString(), baselineCompile);
        assertTrue(ok, "patch should succeed");

        Path patch = Paths.get("rlfixer.patch");
        assertTrue(Files.exists(patch));
        String diff = Files.readString(patch);

        // should promote the SAME variable ('sock') into the TWR header
        assertTrue(diff.contains("try (Socket sock = new Socket("),
                   "patch must use existing var, no temp");

        // verify file reverted
        List<String> now = Files.readAllLines(srcFile);
        assertEquals(originalSource, now, "file content must match original after revert");
    }
}
