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
    void theRepairStagesGetOnlyWarningsOutsideSuppressedCode() throws IOException {
        String diagnostics = warning(file, lineOf("// returned")) + warning(file, lineOf("// unmarked local"))
                + warning(file, lineOf("// nested")) + "3 warnings\n";
        RepairScope scope = RepairScope.of(diagnostics, List.of(), StandardCharsets.UTF_8);

        String repairable = scope.repairable(diagnostics);

        assertThat(CheckerWarning.parseAll(repairable)).extracting(CheckerWarning::line).containsExactly(lineOf("// unmarked local"));
        assertThat(repairable).endsWith("3 warnings\n");
        assertThat(scope.withheldWarnings(diagnostics, directory)).extracting(RepairScope.Withheld::reason)
                .containsExactly(RepairScope.SUPPRESSED, RepairScope.SUPPRESSED);
    }

    @Test
    void noWarningInAGeneratedFileGoesToTheRepairStages() throws IOException {
        Path generated = Files.writeString(directory.resolve("Generated.java"), "class Generated {}\n");
        String diagnostics = warning(generated, 1) + warning(file, lineOf("// unmarked local"));
        RepairScope scope = RepairScope.of(diagnostics, List.of(generated), StandardCharsets.UTF_8);

        assertThat(CheckerWarning.parseAll(scope.repairable(diagnostics))).extracting(CheckerWarning::file).containsExactly(file.toString());
        assertThat(scope.withheldWarnings(diagnostics, directory)).containsExactly(new RepairScope.Withheld("Generated.java", 1, "generated"));
    }

    private static String warning(Path source, int line) {
        return source + ":" + line + ": warning: [required.method.not.called] $$ 4 $$ method close $$ open() $$ java.io.InputStream $$ "
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
