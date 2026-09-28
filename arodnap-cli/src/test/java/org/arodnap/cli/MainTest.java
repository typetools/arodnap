package org.arodnap.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MainTest {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String errors() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void printsItsVersion() {
        assertThat(run("--version")).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).startsWith("arodnap ");
    }

    @Test
    void aCommandIsRequired() {
        assertThat(run()).isEqualTo(2);
        assertThat(errors()).contains("Usage: arodnap");
    }

    @Test
    void theProjectDirectoryIsRequired() {
        assertThat(run("repair")).isEqualTo(2);
        assertThat(errors()).contains("Missing required parameter: 'REPO'");
    }

    @Test
    void applyTakesNoBuildCommand() {
        assertThat(run("apply", "--patch-dir", "patches", ".", "--", "./build.sh")).isEqualTo(2);
        assertThat(errors()).contains("apply does not take a build command");
    }

    @Test
    void timeLimitsMustBePositive() {
        assertThat(run("repair", "--stage-timeout", "0", ".")).isEqualTo(2);
        assertThat(errors()).contains("--stage-timeout: expected a positive number of seconds, got 0");
    }

    @Test
    void fieldTransformationsTakeOneOfThreeModes() {
        assertThat(run("repair", "--field-transformations", "some", ".")).isEqualTo(2);
        assertThat(errors()).contains("--field-transformations: expected resources, all or off, got some");
    }
}
