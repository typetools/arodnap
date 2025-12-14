package com.rlc.fixer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class TryWrapAndFinallySingleStmt {

    @TempDir
    Path tempDir;
    Path projectRoot, srcFile;
    List<String> originalSource, baseline;

    @BeforeEach
    void setup() throws Exception {
        projectRoot = tempDir.resolve("proj");
        srcFile = projectRoot.resolve("src/com/realtimecep/scribe/ScribeClientQuickStartMainClass.java");
        Files.createDirectories(srcFile.getParent());

        String code = String.join("\n",
                "package com.realtimecep.scribe;",
                "",
                "import java.net.Socket;",
                "",
                "public class Foo {",
                "    private static final String scribeHost = \"localhost\";",
                "    private static final int scribePort = 1463;",
                "",
                "    public Foo start() {",
                "        Socket socket = new Socket(scribeHost, scribePort);",
                "        return this;",
                "    }",
                "}");

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
    void tryWrapAndFinally_singlestmttest_allInOnePatch() throws Exception {
        // locate anchors by content so the test is robust to minor line shifts
        List<String> lines = Files.readAllLines(srcFile);

        int startLine = -1; // RLFixer: "Add following code above line: <start>"
        int endLine = -1; // RLFixer: "Add following code after/below line: <end> finally{"
        int deleteLn = -1; // RLFixer: "Delete Line number <n>"

        for (int i = 0; i < lines.size(); i++) {
            String L = lines.get(i);
            if (startLine < 0 && L.contains("new Socket(scribeHost, scribePort)")) {
                startLine = i + 1; // 1-based
            }
            if (endLine < 0 && L.contains("new Socket(scribeHost, scribePort)")) {
                endLine = i + 1; // 1-based
            }
        }

        assertTrue(startLine > 0, "found start anchor line (new Socket(...))");
        assertTrue(endLine > 0, "found end anchor line (new Socket(...))");

        // Build PromptInfo to reflect the RLFixer hint
        PromptInfo p = new PromptInfo();
        p.leakSourceFile = srcFile.toString();
        p.rlfixerSourceFile = srcFile.toString();
        p.resourceType = "java.net.Socket";
        p.allocationExprText = "socket"; // nested allocation text
        p.finalizerMethod = "close";
        p.cfLeakLine = startLine; // CF line pointing at the nested allocation site
        p.patchType = PatchType.TRY_WRAP_AND_FINALLY;
        p.tryWrapStartLine = startLine;
        p.tryWrapEndLine = endLine;
        p.finallyInsertLine = endLine;
        p.linesToDelete.add(deleteLn); // delete transport.close();

        boolean ok = TryWrapAndFinallyTransformer.apply(
                Collections.singletonList(p),
                projectRoot.toString(),
                baseline);
        assertTrue(ok, "patch should succeed and compile no worse than baseline");

        // patch file exists and indicates try-wrap + finally + close + deletion of
        // transport.close()
        Path patch = Paths.get("rlfixer.patch");
        assertTrue(Files.exists(patch), "patch file should exist");
        String diff = Files.readString(patch);

        // TWR header added (not a plain try/finally)
        assertTrue(diff.contains("try (") || diff.contains("try("), "diff adds try-with-resources header");
        assertTrue(diff.contains("new Socket(scribeHost, scribePort)"), "diff binds nested allocation in TWR header");

        // source file restored after emitting patch
        assertEquals(originalSource, Files.readAllLines(srcFile), "source restored to original after patch emission");
    }
}
