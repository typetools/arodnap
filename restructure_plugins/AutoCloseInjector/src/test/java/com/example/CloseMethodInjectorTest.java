package com.example;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class CloseMethodInjectorTest {

    @Test
    public void testAddCloseMethod() throws IOException {
        Path tempDir = Files.createTempDirectory("closeMethodInjectorTest");

        // Create a sample Java file
        String originalClassContent = 
                "package com.example;\n\n" +
                "public class SampleClass {\n" +
                "    private Stream stream;\n\n" +
                "    public SampleClass(Stream stream) {\n" +
                "        this.stream = stream;\n" +
                "    }\n" +
                "}";
        Path originalFilePath = tempDir.resolve("SampleClass.java");
        Files.write(originalFilePath, originalClassContent.getBytes());

        CompilationUnit cu = StaticJavaParser.parse(originalFilePath.toFile());

        // Apply the CloseMethodInjector
        CloseMethodInjector.addCloseMethod(cu, originalFilePath.toString(), "SampleClass", Set.of("stream#close"));

        Path modifiedFilePath = originalFilePath.resolveSibling(originalFilePath.getFileName().toString() + ".modified");

        String modifiedContent = new String(Files.readAllBytes(modifiedFilePath));

        assertTrue(modifiedContent.contains("implements AutoCloseable"), "Class should implement AutoCloseable");
        assertTrue(modifiedContent.contains("public void close()"), "Class should have a close method");
        assertTrue(modifiedContent.contains("stream.close();"), "Close method should close the stream");

        Files.deleteIfExists(originalFilePath);
        Files.deleteIfExists(modifiedFilePath);
        Files.deleteIfExists(tempDir);
    }

    @Test
    public void existingCloseMethodKeepsItsIndentation() throws IOException {
        // Shaped like Apktool's ExtFile: close() exists but does not close every owned field.
        Path tempDir = Files.createTempDirectory("closeMethodInjectorTest");
        String original =
                "package com.example;\n\n" +
                "public class Holder implements AutoCloseable {\n" +
                "    private Stream stream;\n" +
                "    private Stream other;\n\n" +
                "    @Override\n" +
                "    public void close() {\n" +
                "        if (other != null) {\n" +
                "            other.close();\n" +
                "        }\n" +
                "    }\n\n" +
                "    public int size() {\n" +
                "        return 0;\n" +
                "    }\n" +
                "}\n";
        Path file = tempDir.resolve("Holder.java");
        Files.write(file, original.getBytes());

        CompilationUnit cu = StaticJavaParser.parse(file.toFile());
        CloseMethodInjector.addCloseMethod(cu, file.toString(), "Holder", Set.of("stream#close"));
        String modified = new String(Files.readAllBytes(file.resolveSibling("Holder.java.modified")));

        assertTrue(modified.contains("\n    @Override\n    public void close() {\n        try {\n"),
                "Rewritten close() should keep the class's indentation:\n" + modified);
        assertTrue(modified.contains("\n    public int size() {\n        return 0;\n    }\n"),
                "Code outside close() should be unchanged:\n" + modified);
    }

    @Test
    public void closeThatAlreadyReleasesEveryFieldIsLeftAlone() throws IOException {
        // Apktool's ExtFile: close() already closes mDirectory, so there is nothing to add.
        Path tempDir = Files.createTempDirectory("closeMethodInjectorTest");
        String original =
                "package com.example;\n\n" +
                "public class Holder implements AutoCloseable {\n" +
                "    private Stream stream;\n\n" +
                "    @Override\n" +
                "    public void close() throws Exception {\n" +
                "        if (stream != null) {\n" +
                "            stream.close();\n" +
                "        }\n" +
                "    }\n" +
                "}\n";
        Path file = tempDir.resolve("Holder.java");
        Files.write(file, original.getBytes());

        CompilationUnit cu = StaticJavaParser.parse(file.toFile());
        CloseMethodInjector.addCloseMethod(cu, file.toString(), "Holder", Set.of("stream#close"));
        String modified = new String(Files.readAllBytes(file.resolveSibling("Holder.java.modified")));

        assertTrue(modified.equals(original), "close() should be unchanged:\n" + modified);
    }
}
