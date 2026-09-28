package com.rlc.fixer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Synthetic test-case: leak is connection.getOutputStream()
 *   – requires temp variable + try-finally
 */
public class OnlyFinallyNestedWriteTest {

    @TempDir Path tempDir;                 // sandbox
    Path srcFile;
    List<String> originalSource;
    List<String> baselineCompile;

    @BeforeEach
    void setup() throws Exception {
        // --- Minimal stub of HttpURLConnection & OutputStream so javac is happy ---
        Path proj = tempDir.resolve("proj");
        Path stubDir = proj.resolve("src/java/net");
        Files.createDirectories(stubDir);
        Files.writeString(stubDir.resolve("HttpURLConnection.java"),
            "package stub.net;" +
            "public class HttpURLConnection {" +
            "  public java.io.OutputStream getOutputStream() throws java.io.IOException { return null; }" +
            "  public int getResponseCode() { return 200; }" +
            "  public java.io.InputStream getInputStream() { return null; }" +
            "  public void setRequestMethod(String m) {}" +
            "  public void setRequestProperty(String k,String v) {}" +
            "  public void setDoOutput(boolean b) {}" +
            "  public void connect() {}" +
            "  public void setConnectTimeout(int t) {}" +
            "  public void setReadTimeout(int t) {}" +
            "  public static final int HTTP_OK = 200;" +
            "}"
        );

        // --- Synthetic class under test ---
        String code = String.join("\n",
            "package demo;",
            "import stub.net.HttpURLConnection;",
            "import java.net.Socket;",
            "public class Demo3 {",
            "  public void flush(byte[] data) throws Exception {",
            "    try {",
            "      HttpURLConnection connection = new HttpURLConnection();",
            "      connection.setDoOutput(true);",
            "      connection.connect();",
            "      connection.getOutputStream().write(data);",        // leak line 9
            "      int code = connection.getResponseCode();",
            "      if (code != HttpURLConnection.HTTP_OK) throw new Exception();",
            "    } catch (Exception e) {",
            "      throw e;",
            "    }",
            "  }",
            "}"
        );
        srcFile = proj.resolve("src/demo/Demo3.java");
        Files.createDirectories(srcFile.getParent());
        Files.write(srcFile, code.getBytes());

        originalSource   = Files.readAllLines(srcFile);
        baselineCompile  = CompilerUtils.compile(proj.toString());

        // delete patch file if present
        Path patch = Paths.get("rlfixer.patch");
        if (Files.exists(patch))
            Files.delete(patch);
    }

    @Test
    void nestedOutputStreamPatch() throws Exception {
        PromptInfo pi = new PromptInfo();
        pi.leakSourceFile      = srcFile.toString();
        pi.rlfixerSourceFile   = srcFile.toString();
        pi.resourceType        = "java.io.OutputStream";
        pi.allocationExprText  = "connection.getOutputStream()";
        pi.cfLeakLine          = 9;
        pi.finallyInsertLine   = 12;          // just after catch in synthetic
        pi.patchType           = PatchType.ONLY_FINALLY;

        boolean ok = OnlyFinallyTransformer.apply(
                Collections.singletonList(pi), tempDir.resolve("proj").toString(), baselineCompile);
        assertTrue(ok, "patch should succeed");

        Path patch = Paths.get("rlfixer.patch");
        assertTrue(Files.exists(patch));
        String diff = Files.readString(patch);

        // Should contain finally block with temp.close()
        assertTrue(diff.contains("finally {"), "diff must add finally");
        assertTrue(diff.contains("__arodnap_temp0.close()"), "temp variable must be closed");

        // file restored
        List<String> now = Files.readAllLines(srcFile);
        assertEquals(originalSource, now, "source file restored");
    }
}
