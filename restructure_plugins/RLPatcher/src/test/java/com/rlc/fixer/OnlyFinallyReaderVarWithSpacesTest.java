package com.rlc.fixer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class OnlyFinallyReaderVarWithSpacesTest {

    @TempDir Path tempDir;
    Path projectRoot, srcFile;
    List<String> originalSource, baseline;

    @BeforeEach
    void setup() throws Exception {
        projectRoot = tempDir.resolve("proj");
        srcFile = projectRoot.resolve("src/ikrs/json/parser/JSONParser.java");
        Files.createDirectories(srcFile.getParent());

        // Minimal reproduction: leak line has trailing spaces
        String code = String.join("\n",
            "package ikrs.json.parser;",
            "import java.io.*;",
            "public class JSONParser {",
            "  public JSONParser(Reader r) {}",
            "  public void parse() throws IOException {}",
            "  public static void main(String[] argv) {",
            "    try {",
            "      Reader reader = new java.io.FileReader( argv[0] );   ", // <-- trailing spaces
            "      new JSONParser(reader).parse();",
            "      reader.close();",
            "    } catch (Exception e) {",
            "      e.printStackTrace();",
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
    void onlyFinally_whenLeakLineHasTrailingSpaces() throws Exception {
        // Auto-detect leak line (ignore spaces)
        int leakLine = 0;
        List<String> lines = Files.readAllLines(srcFile);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).replace(" ", "")
                    .contains("Readerreader=newjava.io.FileReader(argv[0]);")) {
                leakLine = i + 1; // 1-based
                break;
            }
        }
        assertTrue(leakLine > 0, "leak line should be detected");

        PromptInfo p = new PromptInfo();
        p.leakSourceFile      = srcFile.toString();
        p.rlfixerSourceFile   = srcFile.toString();
        p.resourceType        = "java.io.Reader";
        p.allocationExprText  = "reader";
        p.finalizerMethod     = "close";
        p.cfLeakLine          = leakLine;
        p.finallyInsertLine   = leakLine + 2;
        p.patchType           = PatchType.ONLY_FINALLY;

        boolean ok = OnlyFinallyTransformer.apply(Collections.singletonList(p), projectRoot.toString(), baseline);
        assertTrue(ok, "patch should succeed");

        Path patch = Paths.get("rlfixer.patch");
        assertTrue(Files.exists(patch), "patch file should exist");
        String diff = Files.readString(patch);
        assertTrue(diff.contains("try (Reader"), "diff must add try-with-resources");

        // Verify original source restored
        assertEquals(originalSource, Files.readAllLines(srcFile));
    }
}
