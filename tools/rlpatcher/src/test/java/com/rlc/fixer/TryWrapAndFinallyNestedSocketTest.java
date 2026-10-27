package com.rlc.fixer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class TryWrapAndFinallyNestedSocketTest {

    @TempDir
    Path tempDir;
    Path projectRoot, srcFile;
    List<String> originalSource, baseline;

    @BeforeEach
    void setup() throws Exception {
        projectRoot = tempDir.resolve("proj");
        srcFile = projectRoot.resolve("src/com/realtimecep/scribe/ScribeClientQuickStartMainClass.java");
        Files.createDirectories(srcFile.getParent());

        // Minimal, self-contained repro: uses local stubs for Thrift classes so it
        // compiles.
        // Pattern under test: nested allocation new Socket(host,port) inside new
        // TSocket(...),
        // RLFixer wants: try { ... } finally { <NEW_VARIABLE>.close(); } and delete a
        // close() line.
        String code = String.join("\n",
                "package com.realtimecep.scribe;",
                "",
                "import java.net.Socket;",
                "import java.util.ArrayList;",
                "import java.util.List;",
                "",
                "public class ScribeClientQuickStartMainClass {",
                "    private static final String scribeHost = \"localhost\";",
                "    private static final int scribePort = 1463;",
                "    private static final String scribeCategory = \"test\";",
                "",
                "    public static void main(String[] args) throws Exception {",
                "        List<LogEntry> logEntries = new ArrayList<LogEntry>(1);",
                "        TSocket sock = new TSocket(new Socket(scribeHost, scribePort)); // nested allocation target",
                "        TFramedTransport transport = new TFramedTransport(sock);",
                "        TBinaryProtocol protocol = new TBinaryProtocol(transport, false, false);",
                "",
                "        scribe.Client client = new scribe.Client(protocol, protocol);",
                "",
                "        String message = String.format(\"%s\", \"Message from My Test Client Application\");",
                "        LogEntry entry = new LogEntry(scribeCategory, message);",
                "        logEntries.add(entry);",
                "        client.Log(logEntries); // end anchor",
                "",
                "        transport.close(); // to be deleted by hint",
                "    }",
                "}",
                "",
                "// ----- minimal stubs so the test compiles -----",
                "class TSocket {",
                "    TSocket(Socket s) {}",
                "}",
                "class TFramedTransport {",
                "    TFramedTransport(TSocket s) {}",
                "    void close() {}",
                "}",
                "class TBinaryProtocol {",
                "    TBinaryProtocol(TFramedTransport t, boolean a, boolean b) {}",
                "}",
                "class LogEntry {",
                "    LogEntry(String c, String m) {}",
                "}",
                "class scribe {",
                "    static class Client {",
                "        Client(TBinaryProtocol a, TBinaryProtocol b) {}",
                "        void Log(List<LogEntry> entries) {}",
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
    void tryWrapAndFinally_forNestedSocket_allInOnePatch() throws Exception {
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
            if (endLine < 0 && L.contains("client.Log(logEntries)")) {
                endLine = i + 1; // 1-based
            }
            if (deleteLn < 0 && L.contains("transport.close()")) {
                deleteLn = i + 1; // 1-based
            }
        }

        assertTrue(startLine > 0, "found start anchor line (new Socket(...))");
        assertTrue(endLine > 0, "found end anchor line (client.Log(...))");
        assertTrue(deleteLn > 0, "found delete line (transport.close())");

        // Build PromptInfo to reflect the RLFixer hint
        PromptInfo p = new PromptInfo();
        p.leakSourceFile = srcFile.toString();
        p.rlfixerSourceFile = srcFile.toString();
        p.resourceType = "java.net.Socket";
        p.allocationExprText = "new Socket(scribeHost, scribePort)"; // nested allocation text
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
        assertTrue(diff.contains("__arodnap_temp0"), "diff introduces temp resource var in TWR header");
        assertTrue(diff.contains("new Socket(scribeHost, scribePort)"), "diff binds nested allocation in TWR header");

        // Deleted close call should appear as a removed line
        assertTrue(
                diff.contains("-        transport.close()")
                        || diff.contains("-\ttransport.close()")
                        || diff.matches("(?s).*\\n-\\s*transport\\.close\\(\\);\\n.*"),
                "diff deletes transport.close()");

        // source file restored after emitting patch
        assertEquals(originalSource, Files.readAllLines(srcFile), "source restored to original after patch emission");
    }
}
