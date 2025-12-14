package com.rlc.fixer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OnlyFinallyTest {

    @TempDir
    Path tempDir; // JUnit-managed sandbox
    Path srcFile;
    List<String> baseline;
    List<String> originalSource; 

    @BeforeEach
    void setup() throws Exception {

        // ── 1. Write synthetic Java source
        String code = String.join("\n",
                "package demo;",
                "import java.net.Socket;",
                "import org.apache.thrift.transport.TSocket;",
                "public class Demo {",
                "  public static void main(String[] a) {",
                "    try {",
                "      TSocket sock = new TSocket(new Socket(\"localhost\", 9090));", // leak line → 7
                "      System.out.println(sock);",
                "    } catch (Exception e) {",
                "      e.printStackTrace();",
                "    }",
                "  }",
                "}");

        // projectRoot/src/demo/Demo.java
        Path projectRoot = tempDir.resolve("proj");
        srcFile = projectRoot.resolve("src/demo/Demo.java");
        Files.createDirectories(srcFile.getParent());
        Files.write(srcFile, code.getBytes());
        originalSource = Files.readAllLines(srcFile);

        Path thriftDir = projectRoot.resolve("src/org/apache/thrift/transport");
        Files.createDirectories(thriftDir);
        Files.writeString(thriftDir.resolve("TSocket.java"),
                "package org.apache.thrift.transport;" +
                        "public class TSocket {" +
                        "  public TSocket(java.net.Socket s) {}" +
                        "}");

        // ── 2. Compile baseline
        baseline = CompilerUtils.compile(projectRoot.toString());

        // delete patch file if present
        Path patch = Paths.get("rlfixer.patch");
        if (Files.exists(patch))
            Files.delete(patch);
    }

    @Test
    void syntheticTryWithResourcesPatch() throws Exception {
        // ── 3. Build PromptInfo that mimics the CF/RLFixer data
        PromptInfo p = new PromptInfo();
        p.leakSourceFile = srcFile.toString();
        p.rlfixerSourceFile = srcFile.toString();
        p.resourceType = "java.net.Socket";
        p.allocationExprText = "new Socket(\"localhost\", 9090)";
        p.cfLeakLine = 7; // line numbers from synthetic code above
        p.finallyInsertLine = 11; // RLFixer said “add finally below line 11”
        p.patchType = PatchType.ONLY_FINALLY;

        // ── 4. Run transformer
        boolean ok = OnlyFinallyTransformer.apply(Collections.singletonList(p), tempDir.resolve("proj").toString(), baseline);
        assertTrue(ok, "patch should succeed");

        // ── 5. Verify diff was produced and file reverted
        Path patch = Paths.get("rlfixer.patch");
        assertTrue(Files.exists(patch));
        String diff = Files.readString(patch);
        assertTrue(diff.contains("try (java.net.Socket __arodnap_temp0 = new Socket"), "diff must show TWR header");

        // Source restored?
        List<String> current = Files.readAllLines(srcFile);
        assertEquals(originalSource, current, "file reverted to original content");
    }
}
