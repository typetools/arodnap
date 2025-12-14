package com.rlc.fixer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class OnlyFinallyConstructorLeakTest {

    @TempDir Path tempDir;
    Path projectRoot, srcFile;
    List<String> originalSource, baseline;

    @BeforeEach
    void setup() throws Exception {
        projectRoot = tempDir.resolve("proj");
        srcFile = projectRoot.resolve("src/demo/StationServerImpl.java");
        Files.createDirectories(srcFile.getParent());

        // Minimal repro: leak occurs in a CONSTRUCTOR, via inline new FileInputStream(...)
        String code = String.join("\n",
            "package demo;",
            "public class StationServerImpl {",
            "  private java.util.Properties udpProperties = new java.util.Properties();",
            "  public StationServerImpl() {",
            "    try {",
            "      udpProperties.load(new java.io.FileInputStream(\"./udp.properties\"));", // <-- leak here
            "    } catch (java.io.IOException e) {",
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
    void onlyFinally_whenLeakIsInConstructor() throws Exception {
        // locate leak line (1-based)
        int leakLine = 0;
        List<String> lines = Files.readAllLines(srcFile);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("new java.io.FileInputStream(\"./udp.properties\")")) {
                leakLine = i + 1;
                break;
            }
        }
        assertTrue(leakLine > 0, "leak line should be detected");

        // Build PromptInfo similar to CF/RLFixer
        PromptInfo p = new PromptInfo();
        p.leakSourceFile      = srcFile.toString();
        p.rlfixerSourceFile   = srcFile.toString();
        p.resourceType        = "java.io.FileInputStream";
        p.allocationExprText  = "new java.io.FileInputStream(\"./udp.properties\")";
        p.finalizerMethod     = "close";
        p.cfLeakLine          = leakLine;
        // any line within/at end of the same try is fine; keep it safely after the leak
        p.finallyInsertLine   = leakLine + 4;
        p.patchType           = PatchType.ONLY_FINALLY;

        boolean ok = OnlyFinallyTransformer.apply(Collections.singletonList(p), projectRoot.toString(), baseline);
        assertTrue(ok, "patch should succeed for constructor leak");

        // verify a patch was emitted and it closes the resource
        Path patch = Paths.get("rlfixer.patch");
        assertTrue(Files.exists(patch), "patch file should exist");
        String diff = Files.readString(patch);
        assertTrue(diff.contains("try ("), "diff must add try-with-resources");


        // source restored after patch emission
        assertEquals(originalSource, Files.readAllLines(srcFile));
    }
}
