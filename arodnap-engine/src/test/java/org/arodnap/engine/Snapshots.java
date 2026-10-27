package org.arodnap.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Approved copies of output files, in src/test/resources/snapshots. A test's output, with what
 * differs between runs replaced (directories, times, Arodnap's version), must equal its copy.
 * When an output changes on purpose, rewrite the copies and review the diff:
 *
 * <pre>mvn test -Darodnap.updateSnapshots=true</pre>
 */
public final class Snapshots {
    private static final Path DIRECTORY = Path.of("src/test/resources/snapshots");
    private static final Pattern TIMESTAMP = Pattern.compile("\"\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(\\.\\d+)?(Z|[+-]\\d\\d:\\d\\d)?\"");
    private static final Pattern DURATION = Pattern.compile("(\"[a-z_]*(seconds|duration)[a-z_]*\": )[0-9.eE+-]+");

    private final Map<String, String> replacements = new LinkedHashMap<>();

    /** Replace {@code text} with {@code placeholder} in every snapshot this checks. */
    public Snapshots replacing(String text, String placeholder) {
        replacements.put(text, placeholder);
        return this;
    }

    /** Checks {@code actual} against the approved copy {@code name}, or rewrites the copy. */
    public void check(String name, String actual) {
        String normalized = normalize(actual);
        Path approved = DIRECTORY.resolve(name);
        try {
            if (Boolean.getBoolean("arodnap.updateSnapshots")) {
                Files.createDirectories(approved.getParent());
                Files.writeString(approved, normalized, StandardCharsets.UTF_8);
                return;
            }
            assertThat(approved).as("no approved copy of %s; create it with -Darodnap.updateSnapshots=true", name).isRegularFile();
            assertThat(normalized).as("%s changed; if on purpose, update it with -Darodnap.updateSnapshots=true", name)
                    .isEqualTo(Files.readString(approved, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Checks the file {@code path} against the approved copy {@code name}. */
    public void checkFile(String name, Path path) {
        try {
            check(name, Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String normalize(String text) {
        for (Map.Entry<String, String> replacement : replacements.entrySet()) {
            text = text.replace(replacement.getKey(), replacement.getValue());
        }
        text = TIMESTAMP.matcher(text).replaceAll("\"{TIME}\"");
        return DURATION.matcher(text).replaceAll("$10");
    }
}
