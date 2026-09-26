package com.rlc.fixer;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TypeNamesTest {

    private static final CompilationUnit FILE = StaticJavaParser.parse(String.join("\n",
            "package demo;",
            "import java.io.FileInputStream;",
            "import java.io.*;",
            "import other.Reader;",
            "class Main { static class Handle {} }"));

    @Test
    void importedAndSamePackageTypesUseTheirSimpleName() {
        assertEquals("FileInputStream", TypeNames.inFile(FILE, "java.io.FileInputStream"));
        assertEquals("Channel", TypeNames.inFile(FILE, "demo.Channel"));
    }

    @Test
    void otherTypesStayQualified() {
        // Only on-demand imported, shadowed by another import, or declared in the file.
        assertEquals("java.io.InputStream", TypeNames.inFile(FILE, "java.io.InputStream"));
        assertEquals("java.io.Reader", TypeNames.inFile(FILE, "java.io.Reader"));
        assertEquals("demo.Main.Handle", TypeNames.inFile(FILE, "demo.Main$Handle"));
    }
}
