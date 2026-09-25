package org.arodnap.cli.capture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Arodnap's recording javac. The build finds a {@code javac} script first on PATH that runs this
 * class: it appends the invocation, with {@code @argfiles} expanded, as a JSON line to
 * {@code $ARODNAP_CAPTURE_FILE}, then runs the real javac ({@code $ARODNAP_REAL_JAVAC}) with the
 * same arguments and exits with its exit code. It uses only the JDK, so it starts quickly.
 */
public final class JavacRecorder {
    private JavacRecorder() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        String captureFile = System.getenv("ARODNAP_CAPTURE_FILE");
        String realJavac = System.getenv("ARODNAP_REAL_JAVAC");
        if (captureFile != null) {
            StringBuilder json = new StringBuilder("{\"cwd\": ").append(quote(Path.of("").toAbsolutePath().toString())).append(", \"args\": [");
            List<String> expanded = expand(List.of(args));
            for (int i = 0; i < expanded.size(); i++) {
                json.append(i == 0 ? "" : ", ").append(quote(expanded.get(i)));
            }
            json.append("]}\n");
            Files.writeString(Path.of(captureFile), json, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        List<String> command = new ArrayList<>();
        command.add(realJavac == null ? "javac" : realJavac);
        command.addAll(List.of(args));
        Process javac = new ProcessBuilder(command).inheritIO().start();
        System.exit(javac.waitFor());
    }

    /** Arguments with every existing {@code @file} replaced by the arguments it holds, recursively. */
    static List<String> expand(List<String> args) throws IOException {
        List<String> expanded = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("@") && Files.isRegularFile(Path.of(arg.substring(1)))) {
                expanded.addAll(expand(split(Files.readString(Path.of(arg.substring(1)), StandardCharsets.UTF_8))));
            } else {
                expanded.add(arg);
            }
        }
        return expanded;
    }

    /** Splits text into words like a POSIX shell (Python's shlex.split): quotes group, backslashes escape. */
    static List<String> split(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean inWord = false;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote == '\'') {
                if (c == '\'') {
                    quote = 0;
                } else {
                    word.append(c);
                }
            } else if (quote == '"') {
                if (c == '"') {
                    quote = 0;
                } else if (c == '\\' && i + 1 < text.length() && "\\\"$`\n".indexOf(text.charAt(i + 1)) >= 0) {
                    word.append(text.charAt(++i));
                } else {
                    word.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                inWord = true;
            } else if (c == '\\' && i + 1 < text.length()) {
                word.append(text.charAt(++i));
                inWord = true;
            } else if (Character.isWhitespace(c)) {
                if (inWord) {
                    words.add(word.toString());
                    word.setLength(0);
                    inWord = false;
                }
            } else {
                word.append(c);
                inWord = true;
            }
        }
        if (inWord) {
            words.add(word.toString());
        }
        return words;
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
