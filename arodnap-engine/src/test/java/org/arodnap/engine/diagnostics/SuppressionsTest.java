package org.arodnap.engine.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuppressionsTest {
    private static final String SOURCE = """
            package demo;

            import java.io.InputStream;

            public class Forms {
                @SuppressWarnings("resource")
                InputStream field = open(); // field

                @SuppressWarnings({"unchecked", "resource"})
                InputStream returned() {
                    return open(); // returned
                }

                @SuppressWarnings(value = "all")
                void all() {
                    open(); // all
                }

                void locals() {
                    @SuppressWarnings("resource") InputStream kept = open(); // local
                    InputStream other = open(); // unmarked local
                }

                @SuppressWarnings("unchecked")
                void unrelated() {
                    open(); // unrelated key
                }

                @SuppressWarnings("resource")
                static class Nested {
                    void use() {
                        open(); // nested
                    }
                }

                static InputStream open() {
                    return null;
                }
            }
            """;

    @TempDir
    Path directory;
    Path file;
    Suppressions suppressions;

    @BeforeEach
    void setUp() throws IOException {
        file = Files.writeString(directory.resolve("Forms.java"), SOURCE);
        suppressions = Suppressions.find(Set.of(file), StandardCharsets.UTF_8);
    }

    @Test
    void everyDeclarationMarkedResourceOrAllIsSuppressed() {
        for (String marker : List.of("// field", "// returned", "// all", "// local", "// nested")) {
            assertThat(suppressions.covers(file, lineOf(marker))).as(marker).isTrue();
        }
    }

    @Test
    void codeOutsideThemIsNot() {
        for (String marker : List.of("// unmarked local", "// unrelated key", "return null;")) {
            assertThat(suppressions.covers(file, lineOf(marker))).as(marker).isFalse();
        }
    }

    @Test
    void theRepairableOutputLeavesOutOnlySuppressedWarnings() {
        String diagnostics = warning(lineOf("// returned")) + warning(lineOf("// unmarked local")) + warning(lineOf("// nested"))
                + "3 warnings\n";

        String repairable = suppressions.repairable(diagnostics);

        assertThat(CheckerWarning.parseAll(repairable)).extracting(CheckerWarning::line).containsExactly(lineOf("// unmarked local"));
        assertThat(repairable).endsWith("3 warnings\n");
    }

    private String warning(int line) {
        return file + ":" + line + ": warning: [required.method.not.called] $$ 4 $$ method close $$ open() $$ java.io.InputStream $$ "
                + "possible exceptional exit $$ ( 1, 2 ) $$ [required.method.not.called]\n        open();\n        ^\n";
    }

    private static int lineOf(String marker) {
        List<String> lines = SOURCE.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(marker)) {
                return i + 1;
            }
        }
        throw new IllegalArgumentException(marker);
    }
}
