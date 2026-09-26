package com.rlc.fixer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Resource Leak Checker's warning text differs between Checker Framework releases;
 * the parser must extract the same facts from both.
 */
class PromptParserWarningFormatTest {

    private static final String HINT = "0] /p/src/Demo.java; Line number 10\nvim +10 /p/src/Demo.java\n";

    // Checker Framework 3.49: "(key)" and a message ending in "The type of object is: ...".
    private static final String CF_3_49 = String.join("\n",
            "/p/src/Demo.java:10: warning: (required.method.not.called) $$ 4 $$ method close $$ stream $$ "
                    + "java.io.FileInputStream $$ possible exceptional exit $$ ( 207, 258 ) $$ "
                    + "@MustCall method close may not have been invoked on stream or any of its aliases.",
            "  The type of object is: java.io.FileInputStream.",
            "  Reason for going out of scope: possible exceptional exit");

    // Checker Framework 4.2.3: "[key]" and no message text; the type is only in the $$ arguments.
    private static final String CF_4_2 =
            "/p/src/Demo.java:10: warning: [required.method.not.called] $$ 4 $$ method close $$ stream $$ "
                    + "java.io.FileInputStream $$ possible exceptional exit $$ ( 207, 258 ) $$ "
                    + "[required.method.not.called]";

    @Test
    void bothFormatsYieldTheSameLeakFacts() {
        for (String cf : List.of(CF_3_49, CF_4_2)) {
            PromptInfo info = parseOne(cf);
            assertEquals("/p/src/Demo.java", info.leakSourceFile);
            assertEquals(10, info.cfLeakLine);
            assertEquals("close", info.finalizerMethod);
            assertEquals("stream", info.allocationExprText);
            assertEquals("java.io.FileInputStream", info.resourceType);
        }
    }

    @Test
    void genericResourceTypesKeepOnlyTheirTypeName() {
        // Seen on Apache Commons CSV: the type field carries capture variables.
        String cf = CF_4_2.replace("java.io.FileInputStream", "org.apache.commons.io.function.IOStream<capture#664 of ?>");
        assertEquals("org.apache.commons.io.function.IOStream", parseOne(cf).resourceType);
    }

    @Test
    void nestedResourceTypesKeepTheirBinaryName() {
        String cf = CF_4_2.replace("java.io.FileInputStream", "demo.Outer$Handle");
        assertEquals("demo.Outer$Handle", parseOne(cf).resourceType);
    }

    private static PromptInfo parseOne(String cfBlock) {
        String json = "{\"CF Leaks\": [" + quote(cfBlock) + "], \"RLFixer hint\": [" + quote(HINT) + "]}";
        List<PromptInfo> infos = PromptParser.parseAll(json);
        assertEquals(1, infos.size());
        return infos.get(0);
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
