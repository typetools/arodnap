package com.rlc.fixer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class OnlyFinallyVarNameLeakMinimalTest {

    @TempDir Path tempDir;
    Path projectRoot, srcFile;
    List<String> originalSource, baseline;

    @BeforeEach
    void setup() throws Exception {
        projectRoot = tempDir.resolve("proj");
        srcFile = projectRoot.resolve("src/demo/DemoImg.java");
        Files.createDirectories(srcFile.getParent());

        // minimal, fully-qualified JDK-only code
        String code = String.join("\n",
            "package demo;",
            "public class DemoImg {",
            "  public void run() {",
            "    try {",
            "      java.io.File f = new java.io.File(\"x.png\");",
            "      javax.imageio.ImageWriter w = javax.imageio.ImageIO.getImageWritersByFormatName(\"png\").next();",
            "      javax.imageio.stream.ImageOutputStream stream = javax.imageio.ImageIO.createImageOutputStream(f);",
            "      w.setOutput(stream);",
            "      javax.imageio.ImageWriteParam p = w.getDefaultWriteParam();",
            "      w.write(null, new javax.imageio.IIOImage(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_ARGB), null, null), p);",
            "    } catch (Exception e) {",
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
    void variableNameLeak_onlyFinally() throws Exception {
        // find the line with the declaration (stream = createImageOutputStream)
        int leakLine = 0;
        List<String> lines = Files.readAllLines(srcFile);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("createImageOutputStream")) { leakLine = i + 1; break; }
        }
        assertTrue(leakLine > 0);

        PromptInfo p = new PromptInfo();
        p.leakSourceFile      = srcFile.toString();
        p.rlfixerSourceFile   = srcFile.toString();
        p.resourceType        = "javax.imageio.stream.ImageOutputStream";
        p.allocationExprText  = "stream";              // <— CF reported the variable, not the ctor call
        p.cfLeakLine          = leakLine;
        p.finallyInsertLine   = leakLine + 6;          // inside same try; any later line works
        p.patchType           = PatchType.ONLY_FINALLY;

        boolean ok = OnlyFinallyTransformer.apply(Collections.singletonList(p), projectRoot.toString(), baseline);
        assertTrue(ok, "patch should succeed for variable-name leak");

        Path patch = Paths.get("rlfixer.patch");
        assertTrue(Files.exists(patch), "patch file should exist");
        String diff = Files.readString(patch);
        assertTrue(diff.contains("finally"), "diff must add finally");
        assertTrue(diff.contains(".close()"), "diff must close the resource");

        // file restored after emitting patch
        assertEquals(originalSource, Files.readAllLines(srcFile));
    }
}
