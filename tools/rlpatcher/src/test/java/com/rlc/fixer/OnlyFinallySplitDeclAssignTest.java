package com.rlc.fixer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class OnlyFinallySplitDeclAssignTest {

    @TempDir Path tempDir;
    Path projectRoot, srcFile;
    List<String> originalSource, baseline;

    @BeforeEach
    void setup() throws Exception {
        projectRoot = tempDir.resolve("proj");
        srcFile = projectRoot.resolve("src/vsdk/toolkit/io/image/ImagePersistence.java");
        Files.createDirectories(srcFile.getParent());

        // Minimal repro of the pattern:
        // - separate decls for bis/fis
        // - assignment: fis = new FileInputStream(...)
        // - use bis = new BufferedInputStream(fis); then ImageIO.read(bis);
        String code = String.join("\n",
            "package vsdk.toolkit.io.image;",
            "import java.io.*;",
            "import java.awt.image.BufferedImage;",
            "import javax.imageio.ImageIO;",
            "public class ImagePersistence {",
            "  public static Object importRGBA(java.io.File inImageFd) throws Exception {",
            "    try {",
            "      BufferedImage bi = null;",
            "      BufferedInputStream bis;",
            "      FileInputStream fis;",
            "      fis = new FileInputStream(inImageFd);",           // <-- leak line (matches CF case)
            "      bis = new BufferedInputStream(fis);",
            "      bi  = ImageIO.read(bis);",
            "      bis.close();",
            "      fis.close();",
            "      return bi;",
            "    } catch (Exception e) {",
            "      e.printStackTrace();",
            "      return null;",
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
    void onlyFinally_whenSplitDeclAndAssignForFIS() throws Exception {
        // Find the leak line (allow spaces)
        int leakLine = 0;
        List<String> lines = Files.readAllLines(srcFile);
        for (int i = 0; i < lines.size(); i++) {
            String flat = lines.get(i).replace(" ", "");
            if (flat.contains("fis=newFileInputStream(")) {
                leakLine = i + 1; // 1-based
                break;
            }
        }
        assertTrue(leakLine > 0, "leak line should be detected");

        PromptInfo p = new PromptInfo();
        p.leakSourceFile     = srcFile.toString();
        p.rlfixerSourceFile  = srcFile.toString();
        p.resourceType       = "java.io.FileInputStream";
        p.allocationExprText = "fis";          // CF reports the variable (not the 'new' expr) in this case
        p.finalizerMethod    = "close";
        p.cfLeakLine         = leakLine;
        p.finallyInsertLine  = leakLine + 5;   // below the try body, safe spot for finally
        p.patchType          = PatchType.ONLY_FINALLY;

        boolean ok = OnlyFinallyTransformer.apply(Collections.singletonList(p), projectRoot.toString(), baseline);
        assertTrue(ok, "patch should succeed for split decl/assign");

        // Verify patch emitted & source restored
        Path patch = Paths.get("rlfixer.patch");
        assertTrue(Files.exists(patch), "patch file should exist");
        String diff = Files.readString(patch);
        assertTrue(diff.contains("finally"), "diff must add finally");
        assertTrue(diff.contains(".close()"), "diff must close the resource");
        assertEquals(originalSource, Files.readAllLines(srcFile), "source should be restored after patching");
    }
}
