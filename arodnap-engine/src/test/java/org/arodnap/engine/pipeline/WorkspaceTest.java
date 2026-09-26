package org.arodnap.engine.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTest {
    @TempDir
    Path temp;

    @Test
    void copiesTheProjectWithoutGitAndKeepsExecutableFiles() throws IOException {
        Path project = Files.createDirectories(temp.resolve("demo"));
        Files.writeString(project.resolve("build.sh"), "#!/bin/sh\n");
        project.resolve("build.sh").toFile().setExecutable(true);
        Files.createDirectories(project.resolve(".git/objects"));
        Files.writeString(project.resolve(".git/HEAD"), "ref: refs/heads/main\n");
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/A.java"), "class A {}\n");

        Path root;
        try (Workspace workspace = Workspace.copyOf(project, false)) {
            root = workspace.root();
            assertThat(root.getFileName().toString()).isEqualTo("demo");
            assertThat(root.resolve("src/A.java")).hasContent("class A {}");
            assertThat(Files.isExecutable(root.resolve("build.sh"))).isTrue();
            assertThat(root.resolve(".git")).doesNotExist();
            // Arodnap's own files live next to the copy, never inside the project.
            assertThat(workspace.stateDirectory().startsWith(root)).isFalse();
            assertThat(workspace.stateDirectory().getParent()).isEqualTo(root.getParent());
        }
        assertThat(root).doesNotExist();
        assertThat(project.resolve(".git/HEAD")).exists();
    }

    @Test
    void aKeptWorkspaceStaysAfterTheRun() throws IOException {
        Path project = Files.createDirectories(temp.resolve("demo"));
        Path root;
        try (Workspace workspace = Workspace.copyOf(project, true)) {
            root = workspace.root();
        }
        assertThat(root).exists();
        Workspace.deleteTree(root.getParent());
    }
}
