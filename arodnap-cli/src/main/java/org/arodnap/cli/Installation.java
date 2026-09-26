package org.arodnap.cli;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.arodnap.cli.capture.BuildRecording;
import org.arodnap.cli.capture.JavacRecorder;
import org.arodnap.engine.tools.ToolCoordinates;
import org.arodnap.engine.tools.Toolchain;

/**
 * Where an installed {@code arodnap} finds the tools it runs: its distribution directory, which
 * the launcher passes as {@code ARODNAP_HOME}. A distribution holds
 *
 * <pre>
 * bin/arodnap          the launcher
 * lib/                 the command line and its libraries
 * tools/               each tool's jar, under its Maven file name
 * checker-framework/   checker.jar, checker-qual.jar and checker-util.jar, side by side
 * stubs/               Arodnap's stub files for the Resource Leak Checker
 * </pre>
 */
public record Installation(Toolchain toolchain, BuildRecording.Hooks hooks) {
    private static final Pattern TESTED_JDK = Pattern.compile("\"\\$\\{java_version}\" = (\\d+) \\]");

    /** Thrown when the tools cannot be found. */
    public static final class NotFoundException extends Exception {
        private static final long serialVersionUID = 1L;

        NotFoundException(String message) {
            super(message);
        }
    }

    /**
     * @param checkerFramework a Checker Framework to use instead of the bundled one: a distribution
     *     directory (with {@code checker/dist/checker.jar}), a directory with {@code checker.jar}, or
     *     the jar itself
     */
    public static Installation locate(Optional<Path> checkerFramework) throws NotFoundException {
        Optional<Path> home = Optional.ofNullable(System.getenv("ARODNAP_HOME")).filter(value -> !value.isEmpty()).map(Path::of)
                .or(() -> Optional.ofNullable(System.getProperty("arodnap.home")).map(Path::of));
        if (home.isEmpty()) {
            throw new NotFoundException("Cannot find Arodnap's tools: run Arodnap with the bin/arodnap of its distribution, "
                    + "or set ARODNAP_HOME to the distribution's directory.");
        }
        return at(home.get().toAbsolutePath().normalize(), checkerFramework);
    }

    static Installation at(Path home, Optional<Path> checkerFramework) throws NotFoundException {
        Path tools = home.resolve("tools");
        if (!Files.isDirectory(tools)) {
            throw new NotFoundException(home + " is not an Arodnap distribution: it has no tools directory.");
        }
        Path checker = checkerFramework.isPresent() ? checkerJar(checkerFramework.get()) : home.resolve("checker-framework").resolve("checker.jar");
        Toolchain toolchain = new Toolchain(checker, home.resolve("stubs"), tool(tools, "close-injector"), tool(tools, "owning-field-fixer"),
                tool(tools, "rlfixer"), tool(tools, "rlpatcher"), tool(tools, "field-transformations"), tool(tools, "error-prone"),
                tool(tools, "error-prone-jdk17"), tool(tools, "dataflow"), checkerVersion(checker), testedJdks(checker));
        BuildRecording.Hooks hooks = new BuildRecording.Hooks(tool(tools, "ant-capture"), tool(tools, "maven-capture"), codeSource(),
                Path.of(System.getProperty("java.home"), "bin", "java"));
        return new Installation(toolchain, hooks);
    }

    private static Path tool(Path tools, String name) {
        return tools.resolve(ToolCoordinates.fileName(name));
    }

    private static Path checkerJar(Path given) throws NotFoundException {
        for (Path candidate : List.of(given, given.resolve("checker.jar"), given.resolve("checker").resolve("dist").resolve("checker.jar"))) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new NotFoundException("No checker.jar in " + given + " (give a Checker Framework distribution directory or its checker.jar).");
    }

    private static String checkerVersion(Path checkerJar) {
        try (JarFile jar = new JarFile(checkerJar.toFile())) {
            String version = jar.getManifest() == null ? null : jar.getManifest().getMainAttributes().getValue("Implementation-Version");
            return version == null ? "unknown" : version;
        } catch (IOException e) {
            return "unknown";
        }
    }

    /** From a Checker Framework distribution's wpi.sh when there is one; the bundled release's list otherwise. */
    private static List<Integer> testedJdks(Path checkerJar) {
        Path distribution = checkerJar.toAbsolutePath().getParent();
        for (int i = 0; i < 2 && distribution != null; i++) {
            distribution = distribution.getParent();
        }
        Path wpi = distribution == null ? null : distribution.resolve("checker").resolve("bin").resolve("wpi.sh");
        if (wpi != null && Files.isRegularFile(wpi)) {
            try {
                Matcher matcher = TESTED_JDK.matcher(Files.readString(wpi, StandardCharsets.UTF_8));
                TreeSet<Integer> tested = new TreeSet<>();
                while (matcher.find()) {
                    tested.add(Integer.parseInt(matcher.group(1)));
                }
                return List.copyOf(tested);
            } catch (IOException e) {
                return List.of();
            }
        }
        return checkerVersion(checkerJar).equals(ToolCoordinates.checkerFrameworkVersion()) ? ToolCoordinates.CHECKER_FRAMEWORK_TESTED_JDKS
                : List.of();
    }

    /** The jar (or classes directory) this class was loaded from: the recording javac's class path. */
    private static Path codeSource() throws NotFoundException {
        try {
            return Path.of(JavacRecorder.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
        } catch (URISyntaxException | NullPointerException e) {
            throw new NotFoundException("Cannot tell where Arodnap is installed: " + e.getMessage());
        }
    }
}
