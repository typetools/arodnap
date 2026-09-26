package org.arodnap.engine.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;

/** Path helpers shared by the engine. */
public final class FilePaths {
    private FilePaths() {}

    /**
     * The absolute path with symbolic links resolved as far as the path exists, and the rest
     * appended (like Python's {@code Path.resolve()}). On macOS this turns {@code /var/...} into
     * {@code /private/var/...}, so paths from different tools compare equal.
     */
    public static Path real(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        try {
            Path resolved = existing.toRealPath();
            return resolved.resolve(existing.relativize(absolute)).normalize();
        } catch (IOException e) {
            return absolute;
        }
    }

    /** Orders paths name by name, like Python sorts paths ("a/b" before "a-b/c"). */
    public static final Comparator<Path> BY_NAMES = (left, right) -> {
        int count = Math.min(left.getNameCount(), right.getNameCount());
        for (int i = 0; i < count; i++) {
            int order = left.getName(i).toString().compareTo(right.getName(i).toString());
            if (order != 0) {
                return order;
            }
        }
        return Integer.compare(left.getNameCount(), right.getNameCount());
    };

    /** {@code path} relative to {@code root} with "/" separators, if it is inside {@code root}. */
    public static Optional<String> relativeTo(Path path, Path root) {
        Path resolved = real(path);
        Path base = real(root);
        if (!resolved.startsWith(base)) {
            return Optional.empty();
        }
        return Optional.of(base.relativize(resolved).toString().replace('\\', '/'));
    }
}
