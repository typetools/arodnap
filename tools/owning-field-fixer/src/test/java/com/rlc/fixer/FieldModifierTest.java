package com.rlc.fixer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

public class FieldModifierTest {

    @Test
    public void rewrittenFieldKeepsIndentationAndIsNotPrecededByADuplicateJavadoc() throws Exception {
        Path file = Files.createTempFile("Sink", ".java");
        Files.writeString(file,
                "class Sink {\n"
                        + "    /** The owned writer. */\n"
                        + "    java.io.FileWriter out;\n"
                        + "\n"
                        + "    Sink(String path) throws java.io.IOException {\n"
                        + "        out = new java.io.FileWriter(path);\n"
                        + "    }\n"
                        + "}\n");

        assertTrue(FieldModifier.tryModifyField(file.toString(), "out", true, true));

        String modified = Files.readString(file);
        assertTrue(modified.contains("    /** The owned writer. */\n    private final java.io.FileWriter out;\n"), modified);
        assertEquals(1, modified.split("The owned writer", -1).length - 1, modified);
        Files.delete(file);
    }
}
