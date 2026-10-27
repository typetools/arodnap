package org.arodnap.engine.patch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Line handling shared by patch creation and application.
 *
 * <p>Text is handled as bytes: files and patches are read with ISO-8859-1, which maps every byte to
 * one character, so contents in any encoding (and invalid UTF-8) survive unchanged, and line
 * endings are never translated.
 */
public final class Lines {
    private Lines() {}

    /**
     * Lines with their endings, split at {@code "\n"} only. A {@code "\r"}, form feed or Unicode
     * line separator inside a line of source code does not end it.
     */
    public static List<String> split(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int newline = text.indexOf('\n'); newline >= 0; newline = text.indexOf('\n', start)) {
            lines.add(text.substring(start, newline + 1));
            start = newline + 1;
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    /** A file's exact contents, one character per byte. */
    public static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
    }

    /** Writes text read with {@link #read} back as the same bytes. */
    public static void write(Path file, String text) throws IOException {
        Files.write(file, text.getBytes(StandardCharsets.ISO_8859_1));
    }
}
