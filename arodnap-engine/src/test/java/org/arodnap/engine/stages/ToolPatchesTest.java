package org.arodnap.engine.stages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.arodnap.engine.files.FilePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolPatchesTest {
    @TempDir
    Path temp;

    @Test
    void namesTheWorkspaceFileOnBothHeaders() throws Exception {
        Path root = FilePaths.real(temp);
        Path source = root.resolve("src/main/java/Demo.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Demo {}\n");
        // As AutoCloseInjector writes it: the workspace file against a temporary copy, with a timestamp.
        String raw = "--- " + source + "\t2026-09-25 10:00:00\n+++ /tmp/patch-123.java\t2026-09-25 10:00:01\n@@ -1 +1 @@\n-class Demo {}\n"
                + "+class Demo implements AutoCloseable {}\n";

        ToolPatches.Normalized normalized = ToolPatches.normalize(raw, root);

        assertThat(normalized.changedFiles()).containsExactly("src/main/java/Demo.java");
        assertThat(normalized.text()).startsWith("--- src/main/java/Demo.java\t2026-09-25 10:00:00\n+++ src/main/java/Demo.java\t2026-09-25 10:00:01\n");
    }

    @Test
    void aFormFeedInsideAPatchedLineDoesNotSplitIt() throws Exception {
        String raw = "--- a/X.java\n+++ a/X.java\n@@ -1 +1 @@\n-x\u000c y\n+z\n";
        assertThat(ToolPatches.normalize(raw, temp).text()).contains("-x\u000c y\n");
    }

    @Test
    void rejectsPathsOutsideTheWorkspace() {
        assertThatThrownBy(() -> ToolPatches.normalize("--- ../X.java\n+++ ../X.java\n@@ -1 +1 @@\n-x\n+y\n", temp))
                .isInstanceOf(StageException.class).hasMessageContaining("outside workspace root");
        assertThatThrownBy(() -> ToolPatches.normalize("no diff here\n", temp)).isInstanceOf(StageException.class)
                .hasMessageContaining("did not contain unified diff file headers");
    }

    @Test
    void anOrphanNewFileHeaderIsAnError() throws IOException {
        assertThatThrownBy(() -> ToolPatches.normalize("+++ X.java\n", temp)).isInstanceOf(StageException.class)
                .hasMessageContaining("unexpected unified diff header order");
    }
}
