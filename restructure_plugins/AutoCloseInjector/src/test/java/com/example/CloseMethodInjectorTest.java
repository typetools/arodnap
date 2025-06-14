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
}
