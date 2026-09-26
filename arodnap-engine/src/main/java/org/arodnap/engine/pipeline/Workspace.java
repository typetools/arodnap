package org.arodnap.engine.pipeline;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.stream.Stream;
import org.arodnap.engine.files.FilePaths;

/**
 * A copy of the project that the pipeline analyzes and repairs; the project itself is never
 * changed. The copy lives in a temporary directory Arodnap owns, next to a state directory for
 * files that must stay out of the project (the recorded build, the analysis classes).
 */
public final class Workspace implements AutoCloseable {
    private static final String STATE_DIRECTORY = "arodnap-state";

    private final Path repoRoot;
    private final Path managedRoot;
    private final Path root;
    private final boolean keep;

    private Workspace(Path repoRoot, Path managedRoot, Path root, boolean keep) {
        this.repoRoot = repoRoot;
        this.managedRoot = managedRoot;
        this.root = root;
        this.keep = keep;
    }

    /** Copies the project (without {@code .git}) into a new temporary directory. */
    public static Workspace copyOf(Path repoRoot, boolean keep) throws IOException {
        Path source = FilePaths.real(repoRoot);
        if (!Files.isDirectory(source)) {
            throw new IOException("The project directory does not exist: " + source);
        }
        Path managed = FilePaths.real(Files.createTempDirectory("arodnap-workspace-"));
        Path copy = managed.resolve(source.getFileName().toString());
        copyTree(source, copy);
        return new Workspace(source, managed, copy, keep);
    }

    /** The project's original directory. */
    public Path repoRoot() {
        return repoRoot;
    }

    /** The copy's root: the same layout as the project. */
    public Path root() {
        return root;
    }

    /** A directory next to the copy for Arodnap's own files. */
    public Path stateDirectory() {
        return managedRoot.resolve(STATE_DIRECTORY);
    }

    /** A repository-relative path inside the copy. */
    public Path resolve(String relativePath) {
        return root.resolve(relativePath);
    }

    @Override
    public void close() {
        if (!keep) {
            deleteTree(managedRoot);
        }
    }

    /** Copies a directory tree (without {@code .git}), keeping file attributes. */
    public static void copyDirectory(Path source, Path target) throws IOException {
        copyTree(source, target);
    }

    static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (!directory.equals(source) && directory.getFileName().toString().equals(".git")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(source.relativize(directory).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (file.getFileName().toString().equals(".git")) {
                    return FileVisitResult.CONTINUE; // a worktree's .git file
                }
                Files.copy(file, target.resolve(source.relativize(file).toString()),
                        StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Deletes a directory tree, ignoring what cannot be deleted. */
    public static void deleteTree(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // left behind in the temporary directory
                }
            });
        } catch (IOException | UncheckedIOException e) {
            // left behind in the temporary directory
        }
    }
}
