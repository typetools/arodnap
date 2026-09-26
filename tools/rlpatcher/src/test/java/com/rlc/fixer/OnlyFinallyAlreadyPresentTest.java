package com.rlc.fixer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class OnlyFinallyAlreadyPresentTest {

    @TempDir Path tempDir;
    Path projectRoot, srcFile;
    List<String> originalSource, baseline;

    @BeforeEach
    void setup() throws Exception {
        projectRoot = tempDir.resolve("proj");
        srcFile = projectRoot.resolve("src/demo/Demo.java");
        Files.createDirectories(srcFile.getParent());

        // Minimal reproduction: variable leak with an existing finally block that has a print
        String code = String.join("\n",
            "package demo;",
            "import java.io.*;",
            "public class Demo {",
            "  public static void main(String[] args) {",
            "    try {",
            "      // Simulate a resource leak",
            "      String f = \"foo.txt\";",
            "      InputStream stream = new FileInputStream(f);", // leak line
            "      stream.read();",
            "    } catch (Exception e) {",
            "      e.printStackTrace();",
            "    } finally {",
            "      System.out.println(\"cleanup started\");",
            "    }",
            "  }",
            "}"
        );
        Files.writeString(srcFile, code);
        originalSource = Files.readAllLines(srcFile);

        baseline = CompilerUtils.compile(projectRoot.toString());

        // delete patch file if present
        Path patch = Paths.get("rlfixer.patch");
        if (Files.exists(patch))
            Files.delete(patch);
    }

    @Test
    void onlyFinally_whenFinallyBlockAlreadyPresent() throws Exception {
        // Detect leak line (robustly ignoring spaces)
        int leakLine = 0;
        List<String> lines = Files.readAllLines(srcFile);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).replace(" ", "")
                    .contains("InputStreamstream=newFileInputStream(f);")) {
                leakLine = i + 1; // 1-based
                break;
            }
        }
        assertTrue(leakLine > 0, "leak line should be detected");

        PromptInfo p = new PromptInfo();
        p.leakSourceFile      = srcFile.toString();
        p.rlfixerSourceFile   = srcFile.toString();
        p.resourceType        = "java.io.InputStream";
        p.allocationExprText  = "stream";
        p.finalizerMethod     = "close";
        p.cfLeakLine          = leakLine;
        p.finallyInsertLine   = leakLine + 5; // after try block
        p.patchType           = PatchType.ONLY_FINALLY;

        boolean ok = OnlyFinallyTransformer.apply(Collections.singletonList(p), projectRoot.toString(), baseline);
        assertTrue(ok, "patch should succeed");

        Path patch = Paths.get("rlfixer.patch");
        assertTrue(Files.exists(patch), "patch file should exist");
        String diff = Files.readString(patch);

        // Ensure we still have the print and now also have close()
        assertTrue(diff.contains("System.out.println(\"cleanup started\")"), "existing finally code should remain");
        assertTrue(diff.contains(".close()"), "close call should be added");
    }
}
