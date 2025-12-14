package com.rlc.fixer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.text.CollationElementIterator;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class OnlyFinallyVarOutsideTryTest {

    @TempDir Path tempDir;
    Path projectRoot, srcFile;
    List<String> originalSource, baseline;

    @BeforeEach
    void setup() throws Exception {
        projectRoot = tempDir.resolve("proj");
        srcFile = projectRoot.resolve("src/be/demmel/fun/jgwcaconstants/Version.java");
        Files.createDirectories(srcFile.getParent());

        // Leak pattern: InputStream declared BEFORE try; finally must close it.
        String code = String.join("\n",
            "package be.demmel.fun.jgwcaconstants;",
            "import java.io.*;",
            "import java.util.*;",
            "public class Version {",
            "  private static final String POM_PATH = \"/pom.properties\";",
            "  private static Properties loadProperties() throws Exception {",
            "    InputStream stream = Version.class.getResourceAsStream(POM_PATH);", // <-- leak line
            "    Properties props = new Properties();",
            "    try {",
            "      props.load(stream);",
            "      return props;",
            "    } catch (Exception ex) {",
            "      throw ex;",
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
    void onlyFinally_whenVarDeclaredOutsideTry() throws Exception {
        // Detect the leak line (tolerant to spacing)
        int leakLine = 0;
        List<String> lines = Files.readAllLines(srcFile);
        for (int i = 0; i < lines.size(); i++) {
            String flat = lines.get(i).replace(" ", "");
            if (flat.contains("InputStreamstream=Version.class.getResourceAsStream(POM_PATH);")) {
                leakLine = i + 1; // 1-based
                break;
            }
        }
        assertTrue(leakLine > 0, "leak line should be detected");

        PromptInfo p = new PromptInfo();
        p.leakSourceFile     = srcFile.toString();
        p.rlfixerSourceFile  = srcFile.toString();
        p.resourceType       = "java.io.InputStream";
        p.allocationExprText = "stream";          // CF reports the variable name here
        p.finalizerMethod    = "close";
        p.cfLeakLine         = leakLine;
        p.finallyInsertLine  = leakLine + 7;      // below the try-block (matches RLFixer suggestion window)
        p.patchType          = PatchType.ONLY_FINALLY;

        boolean ok = OnlyFinallyTransformer.apply(Collections.singletonList(p), projectRoot.toString(), baseline);
        assertTrue(ok, "patch should succeed for var declared outside try");

        Path patch = Paths.get("rlfixer.patch");
        assertTrue(Files.exists(patch), "patch file should exist");
        String diff = Files.readString(patch);
        assertTrue(diff.contains("try (InputStream"), "diff must add try-with-resources");

        // Ensure source reverted after patch emission
        assertEquals(originalSource, Files.readAllLines(srcFile));
    }
}
