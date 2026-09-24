package com.rlc.fixer;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.Expression;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AllocationsTest {

    @TempDir
    Path tempDir;

    // Apache Ivy's IvySettings: the allocation spans two lines, and the Checker Framework
    // prints a shortened expression, so only the source offsets identify it.
    private static final String SOURCE = String.join("\n",
            "class Settings {",
            "  void load(String file) throws Exception {",
            "    typeDefs(new java.io.FileInputStream(check(file,",
            "        \"ivy.typedef.files\")), true);",
            "  }",
            "  String check(String f, String name) { return f; }",
            "  void typeDefs(java.io.InputStream in, boolean b) {}",
            "}",
            "");

    @Test
    void reportedOffsetsIdentifyTheAllocation() throws Exception {
        Path file = tempDir.resolve("Settings.java");
        Files.writeString(file, SOURCE);
        String allocation = "new java.io.FileInputStream(check(file,\n        \"ivy.typedef.files\"))";
        int start = SOURCE.indexOf(allocation);
        int end = start + allocation.length();

        String cf = file + ":3: warning: [required.method.not.called] $$ 4 $$ method close $$ "
                + "\"new java.io.FileInputStream(check(fi...\" $$ java.io.FileInputStream $$ regular method exit $$ "
                + "( " + start + ", " + end + " ) $$ [required.method.not.called]";
        String hint = "vim +3 " + file + "\n";
        String json = "{\"CF Leaks\": [" + quote(cf) + "], \"RLFixer hint\": [" + quote(hint) + "]}";
        PromptInfo info = PromptParser.parseAll(json).get(0);

        CompilationUnit cu = StaticJavaParser.parse(SOURCE);
        List<Expression> found = Allocations.find(cu, info);
        assertEquals(1, found.size());
        assertEquals("new java.io.FileInputStream(check(file, \"ivy.typedef.files\"))", found.get(0).toString());
    }

    @Test
    void expressionTextIsTheFallback() {
        PromptInfo info = new PromptInfo();
        info.allocationExprText = "new java.io.FileInputStream(check(file, \"ivy.typedef.files\"))";
        assertEquals(1, Allocations.find(StaticJavaParser.parse(SOURCE), info).size());

        info.allocationExprText = "\"new java.io.FileInputStream(check(fi...\"";
        assertTrue(Allocations.find(StaticJavaParser.parse(SOURCE), info).isEmpty());
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
