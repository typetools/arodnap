package com.rlc.fixer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Patches keep the surrounding code as it was (comments, blank lines, continuation lines):
 * wrapped code is only indented one level, and added code follows the file's indentation.
 */
class PatchRenderingTest {

    @TempDir
    Path tempDir;
    Path project;
    Path source;

    @BeforeEach
    void cleanPatch() throws Exception {
        Files.deleteIfExists(Paths.get("rlfixer.patch"));
        project = tempDir.resolve("proj");
        source = project.resolve("src/demo/Copy.java");
        Files.createDirectories(source.getParent());
    }

    @Test
    void tryWithResourcesKeepsCommentsBlankLinesAndContinuationLines() throws Exception {
        String before = String.join("\n",
                "package demo;",
                "import java.io.*;",
                "public class Copy {",
                "  static class Channel { InputStream in() { return null; } }",
                "  int copy(Channel channel) throws IOException {",
                "    int total = 0;",
                "",
                "    // wrap the channel stream",
                "    InputStream is = new BufferedInputStream(channel.in(),",
                "        1024);",
                "",
                "    // read everything",
                "    while (is.read() >= 0) {",
                "      total++;",
                "    }",
                "    return total;",
                "  }",
                "}",
                "");
        String after = String.join("\n",
                "package demo;",
                "import java.io.*;",
                "public class Copy {",
                "  static class Channel { InputStream in() { return null; } }",
                "  int copy(Channel channel) throws IOException {",
                "    int total = 0;",
                "",
                "    // wrap the channel stream",
                "    try (java.io.InputStream __arodnap_temp0 = channel.in()) {",
                "      InputStream is = new BufferedInputStream(__arodnap_temp0,",
                "          1024);",
                "",
                "      // read everything",
                "      while (is.read() >= 0) {",
                "        total++;",
                "      }",
                "    }",
                "    return total;",
                "  }",
                "}",
                "");
        assertEquals(after, patched(before, "channel.in()", 9, 9, 15));
    }

    @Test
    void tryFinallyTurnsTheDeclarationIntoAnAssignment() throws Exception {
        // The variable is reassigned, so it cannot be a try-with-resources resource.
        String before = String.join("\n",
                "package demo;",
                "import java.io.*;",
                "public class Copy {",
                "  int copy(File f) throws IOException {",
                "    // open the file",
                "    InputStream in = new FileInputStream(f);",
                "    int first = in.read();",
                "",
                "    in = new ByteArrayInputStream(new byte[0]);",
                "    return first + in.read();",
                "  }",
                "}",
                "");
        String after = String.join("\n",
                "package demo;",
                "import java.io.*;",
                "public class Copy {",
                "  int copy(File f) throws IOException {",
                "    // open the file",
                "    InputStream in = null;",
                "    try {",
                "      in = new FileInputStream(f);",
                "      int first = in.read();",
                "",
                "      in = new ByteArrayInputStream(new byte[0]);",
                "      return first + in.read();",
                "    } finally {",
                "      if (in != null) {",
                "        try {",
                "          in.close();",
                "        } catch (Exception e) {",
                "          e.printStackTrace();",
                "        }",
                "      }",
                "    }",
                "  }",
                "}",
                "");
        assertEquals(after, patched(before, "in", 6, 6, 10));
    }

    @Test
    void finallyAddedToAnExistingTryKeepsItsLayout() throws Exception {
        // Apache Ivy's IvySettings: the stream is opened inside a try that only has a catch.
        String before = String.join("\n",
                "package demo;",
                "import java.io.*;",
                "import java.util.Properties;",
                "public class Copy {",
                "  static class Conn { void prepare() {} InputStream open() throws IOException { return null; } }",
                "  Properties configure(Conn c) {",
                "    Properties props = new Properties();",
                "    try {",
                "      c.prepare();",
                "      // the remote configuration",
                "      props.load(c.open());",
                "    } catch (Exception ex) {",
                "      props = new Properties();",
                "    }",
                "    return props;",
                "  }",
                "}",
                "");
        String after = String.join("\n",
                "package demo;",
                "import java.io.*;",
                "import java.util.Properties;",
                "public class Copy {",
                "  static class Conn { void prepare() {} InputStream open() throws IOException { return null; } }",
                "  Properties configure(Conn c) {",
                "    Properties props = new Properties();",
                "    java.io.InputStream __arodnap_temp0 = null;",
                "    try {",
                "      c.prepare();",
                "      // the remote configuration",
                "      __arodnap_temp0 = c.open();",
                "      props.load(__arodnap_temp0);",
                "    } catch (Exception ex) {",
                "      props = new Properties();",
                "    } finally {",
                "      if (__arodnap_temp0 != null) {",
                "        try {",
                "          __arodnap_temp0.close();",
                "        } catch (Exception e) {",
                "          e.printStackTrace();",
                "        }",
                "      }",
                "    }",
                "    return props;",
                "  }",
                "}",
                "");
        PromptInfo info = prompt("c.open()", 11);
        info.finallyInsertLine = 14;
        info.patchType = PatchType.ONLY_FINALLY;
        assertEquals(after, patched(before, info, OnlyFinallyTransformer::apply));
    }

    @Test
    void declarationMovesIntoTheExistingTry() throws Exception {
        String before = String.join("\n",
                "package demo;",
                "import java.io.*;",
                "public class Copy {",
                "  int first(File f) {",
                "    try {",
                "      InputStream in = new FileInputStream(f);",
                "      // read one byte",
                "      return in.read();",
                "    } catch (IOException e) {",
                "      return -1;",
                "    }",
                "  }",
                "}",
                "");
        String after = String.join("\n",
                "package demo;",
                "import java.io.*;",
                "public class Copy {",
                "  int first(File f) {",
                "    try (InputStream in = new FileInputStream(f)) {",
                "      // read one byte",
                "      return in.read();",
                "    } catch (IOException e) {",
                "      return -1;",
                "    }",
                "  }",
                "}",
                "");
        PromptInfo info = prompt("in", 6);
        info.finallyInsertLine = 11;
        info.patchType = PatchType.ONLY_FINALLY;
        assertEquals(after, patched(before, info, OnlyFinallyTransformer::apply));
    }

    @Test
    void allocationInsideAnExistingResourceBecomesItsOwnResource() throws Exception {
        // Apache Ivy's AbstractFSManifestIterable wraps the leaked stream in a resource.
        String before = String.join("\n",
                "package demo;",
                "import java.io.*;",
                "public class Copy {",
                "  InputStream open(String name) throws IOException { return null; }",
                "  int first(String name) throws IOException {",
                "    try (DataInputStream in = new DataInputStream(open(name))) {",
                "      return in.read();",
                "    }",
                "  }",
                "}",
                "");
        String after = String.join("\n",
                "package demo;",
                "import java.io.*;",
                "public class Copy {",
                "  InputStream open(String name) throws IOException { return null; }",
                "  int first(String name) throws IOException {",
                "    try (java.io.InputStream __arodnap_temp0 = open(name); DataInputStream in = new DataInputStream(__arodnap_temp0)) {",
                "      return in.read();",
                "    }",
                "  }",
                "}",
                "");
        PromptInfo info = prompt("open(name)", 6);
        info.finallyInsertLine = 8;
        info.patchType = PatchType.ONLY_FINALLY;
        assertEquals(after, patched(before, info, OnlyFinallyTransformer::apply));
    }

    private interface Transformer {
        boolean apply(List<PromptInfo> infos, String projectRoot, List<String> baseline) throws Exception;
    }

    private PromptInfo prompt(String allocation, int leakLine) {
        PromptInfo info = new PromptInfo();
        info.leakSourceFile = source.toString();
        info.rlfixerSourceFile = source.toString();
        info.resourceType = "java.io.InputStream";
        info.allocationExprText = allocation;
        info.cfLeakLine = leakLine;
        return info;
    }

    private String patched(String code, String allocation, int leakLine, int start, int end) throws Exception {
        PromptInfo info = prompt(allocation, leakLine);
        info.tryWrapStartLine = start;
        info.tryWrapEndLine = end;
        info.finallyInsertLine = end;
        info.patchType = PatchType.TRY_WRAP_AND_FINALLY;
        return patched(code, info, TryWrapAndFinallyTransformer::apply);
    }

    private String patched(String code, PromptInfo info, Transformer transformer) throws Exception {
        Files.writeString(source, code);
        List<String> baseline = CompilerUtils.compile(project.toString());
        assertTrue(transformer.apply(new java.util.ArrayList<>(List.of(info)), project.toString(), baseline));
        assertEquals(code, Files.readString(source), "the source file is restored");

        Path out = tempDir.resolve("patched.java");
        Process patch = new ProcessBuilder("patch", "-s", "-o", out.toString(), source.toString(), "rlfixer.patch")
                .redirectErrorStream(true).start();
        String output = new String(patch.getInputStream().readAllBytes());
        assertEquals(0, patch.waitFor(), output);
        return Files.readString(out);
    }
}
