package org.arodnap.cli.capture;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class JavacRecorderTest {
    @TempDir
    Path temp;

    /** Argument-file contents and how Python's shlex.split (the old recorder) split them. */
    static Stream<Arguments> shlexCases() throws IOException {
        try (InputStream in = JavacRecorderTest.class.getResourceAsStream("/shlex-cases.json")) {
            JsonNode cases = new ObjectMapper().readTree(in);
            List<Arguments> arguments = new ArrayList<>();
            cases.forEach(item -> {
                List<String> words = new ArrayList<>();
                item.get("words").forEach(word -> words.add(word.asText()));
                arguments.add(Arguments.of(item.get("text").asText(), words));
            });
            return arguments.stream();
        }
    }

    @ParameterizedTest
    @MethodSource("shlexCases")
    void splitsArgumentFilesLikeAPosixShell(String text, List<String> words) {
        assertThat(JavacRecorder.split(text)).isEqualTo(words);
    }

    @Test
    void expandsNestedArgumentFiles() throws IOException {
        Path inner = temp.resolve("inner.txt");
        Files.writeString(inner, "-encoding UTF-8\n\"src/A B.java\"\n");
        Path outer = temp.resolve("outer.txt");
        Files.writeString(outer, "-d out @" + inner + "\n");

        assertThat(JavacRecorder.expand(List.of("-nowarn", "@" + outer, "@missing.txt")))
                .containsExactly("-nowarn", "-d", "out", "-encoding", "UTF-8", "src/A B.java", "@missing.txt");
    }

    @Test
    void quotesJsonStrings() {
        assertThat(JavacRecorder.quote("a\"b\\c\n")).isEqualTo("\"a\\\"b\\\\c\\u000a\"");
    }
}
