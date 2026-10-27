package org.arodnap.engine.jdk;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A JDK Arodnap runs things with.
 *
 * @param home the JDK's home directory
 * @param majorVersion its feature release, e.g. 21
 * @param source where it was found: {@code JAVA_HOME} or {@code PATH}
 */
public record Jdk(Path home, int majorVersion, String source) {
    /** RLFixer is built on WALA 1.8, which needs Java 17 or newer. */
    public static final int RLFIXER_MINIMUM = 17;

    public Path java() {
        return home.resolve("bin").resolve("java");
    }

    public Path javac() {
        return home.resolve("bin").resolve("javac");
    }

    /** The build's {@code --release} and {@code -encoding}, as javac options for Arodnap's own compiles. */
    public static List<String> languageOptions(Optional<Integer> release, Optional<String> encoding) {
        List<String> options = new ArrayList<>();
        release.ifPresent(level -> {
            options.add("--release");
            options.add(Integer.toString(level));
        });
        encoding.filter(name -> !name.isEmpty()).ifPresent(name -> {
            options.add("-encoding");
            options.add(name);
        });
        return options;
    }
}
