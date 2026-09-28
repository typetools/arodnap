package org.arodnap.engine.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Arodnap's stub files for the Resource Leak Checker (for example, a side-effect-free
 * {@code close()}), shipped in the engine and written to a directory for {@code -Astubs}.
 */
public final class Stubs {
    private static final String ROOT = "/org/arodnap/engine/stubs/";

    private Stubs() {}

    /** Writes the stub files into {@code directory} (replacing older copies) and returns it. */
    public static Path extract(Path directory) throws IOException {
        Files.createDirectories(directory);
        String index;
        try (InputStream in = Stubs.class.getResourceAsStream(ROOT + "index.txt")) {
            if (in == null) {
                throw new IOException("The engine's stub index is missing.");
            }
            index = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String name : index.lines().map(String::strip).filter(line -> !line.isEmpty()).toList()) {
            try (InputStream in = Stubs.class.getResourceAsStream(ROOT + name)) {
                if (in == null) {
                    throw new IOException("The engine's stub " + name + " is missing.");
                }
                Files.copy(in, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return directory;
    }
}
