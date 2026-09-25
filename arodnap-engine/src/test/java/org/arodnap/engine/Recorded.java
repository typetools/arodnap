package org.arodnap.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tool outputs recorded from a real run of the coverage fixture (tests/fixtures/gradle-pipeline-coverage),
 * with the run's directories replaced by {@link #WORKSPACE} and {@link #OUT}.
 */
public final class Recorded {
    /** Where the recorded run's copy of the project was. */
    public static final Path WORKSPACE = Path.of("/work/space/gradle-pipeline-coverage");
    /** Where the recorded run's outputs were. */
    public static final Path OUT = Path.of("/work/out");
    public static final Path SOURCE_ROOT = WORKSPACE.resolve("src/main/java");

    private Recorded() {}

    public static String text(String name) {
        try (InputStream in = Recorded.class.getResourceAsStream("/recorded/coverage/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No recorded file " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Copies a recorded file into {@code directory} and returns its path. */
    public static Path copy(String name, Path directory) {
        try {
            Path target = directory.resolve(name);
            Files.createDirectories(target.getParent());
            Files.writeString(target, text(name), StandardCharsets.UTF_8);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
