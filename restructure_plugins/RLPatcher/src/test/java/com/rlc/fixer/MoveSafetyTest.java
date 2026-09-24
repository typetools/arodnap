package com.rlc.fixer;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MoveSafetyTest {

    @TempDir
    Path tempDir;

    @Test
    void allocationThatRunsFirstCanMove() {
        assertTrue(firstEffect("InputStream in = new BufferedInputStream(conn.getInputStream(), 10);",
                "conn.getInputStream()"));
        assertTrue(firstEffect("conn.getOutputStream().write(data);", "conn.getOutputStream()"));
        assertTrue(firstEffect("return new Reader(new FileInputStream(f));", "new FileInputStream(f)"));
        assertTrue(firstEffect("in = (InputStream) open(name + \".txt\");", "open(name + \".txt\")"));
    }

    @Test
    void allocationAfterAnotherEffectCannotMove() {
        // Apache Ivy's BasicURLHandler: getContentEncoding() runs before getInputStream().
        assertFalse(firstEffect("InputStream in = decode(conn.getContentEncoding(), conn.getInputStream());",
                "conn.getInputStream()"));
        assertFalse(firstEffect("x.prepare().use(open());", "open()"));
        assertFalse(firstEffect("use(i++, open());", "open()"));
    }

    @Test
    void conditionallyEvaluatedAllocationCannotMove() {
        assertFalse(firstEffect("if (ready) use(open());", "open()"));
        assertFalse(firstEffect("boolean ok = ready && open() != null;", "open()"));
        assertFalse(firstEffect("InputStream in = ready ? open() : null;", "open()"));
        assertFalse(firstEffect("run(() -> open());", "open()"));
    }

    @Test
    void finallyFormIsNotMaterializedWhenItWouldReorderCalls() throws Exception {
        Path project = tempDir.resolve("proj");
        Path source = project.resolve("src/demo/Fetch.java");
        Files.createDirectories(source.getParent());
        String code = String.join("\n",
                "package demo;",
                "import java.io.*;",
                "public class Fetch {",
                "  static class Conn {",
                "    String encoding() { return null; }",
                "    InputStream open() throws IOException { return null; }",
                "  }",
                "  static InputStream decode(String enc, InputStream in) { return in; }",
                "  int fetch() throws IOException {",
                "    Conn conn = null;",
                "    try {",
                "      conn = new Conn();",
                "      InputStream in = decode(conn.encoding(), conn.open());",
                "      return in.read();",
                "    } catch (IOException e) {",
                "      throw e;",
                "    }",
                "  }",
                "}",
                "");
        Files.writeString(source, code);
        List<String> baseline = CompilerUtils.compile(project.toString());
        Files.deleteIfExists(Paths.get("rlfixer.patch"));

        PromptInfo info = new PromptInfo();
        info.leakSourceFile = source.toString();
        info.rlfixerSourceFile = source.toString();
        info.resourceType = "java.io.InputStream";
        info.allocationExprText = "conn.open()";
        info.cfLeakLine = 13;
        info.finallyInsertLine = 17;
        info.patchType = PatchType.ONLY_FINALLY;

        assertThrows(UnsafeEditException.class, () -> OnlyFinallyTransformer.apply(
                Collections.singletonList(info), project.toString(), baseline));
        assertFalse(Files.exists(Paths.get("rlfixer.patch")));
        assertEquals(code, Files.readString(source));
    }

    private static boolean firstEffect(String statementSource, String allocationSource) {
        Statement statement = StaticJavaParser.parseStatement(statementSource);
        Expression allocation = statement.findFirst(Expression.class,
                e -> e.toString().equals(allocationSource)).orElseThrow();
        return MoveSafety.isFirstEffectOf(allocation, statement);
    }
}
