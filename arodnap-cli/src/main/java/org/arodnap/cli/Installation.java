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
import org.arodnap.engine.tools.Toolchain;

/**
 * Where an installed {@code arodnap} finds the tools it runs.
 *
 * <p>A distribution has them under its home directory ({@code ARODNAP_HOME}, set by the launcher):
 * {@code tools/}, {@code checker-framework/} and {@code stubs/}. Run from a checkout of the
 * repository, they come from the repository's layout instead.
 */
public record Installation(Toolchain toolchain, BuildRecording.Hooks hooks) {
    private static final Pattern TESTED_JDK = Pattern.compile("\"\\$\\{java_version}\" = (\\d+) \\]");
    /** JDKs the bundled Checker Framework release is tested on (its wpi.sh lists them). */
    private static final List<Integer> BUNDLED_TESTED_JDKS = List.of(8, 11, 17, 21, 24, 25, 26);

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
        Path recorder = codeSource();
        Optional<Path> home = Optional.ofNullable(System.getenv("ARODNAP_HOME")).filter(value -> !value.isEmpty()).map(Path::of)
                .or(() -> Optional.ofNullable(System.getProperty("arodnap.home")).map(Path::of));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        if (home.isPresent()) {
            return distribution(home.get(), checkerFramework, recorder, java);
        }
        for (Path directory = recorder; directory != null; directory = directory.getParent()) {
            if (Files.isDirectory(directory.resolve("restructure_plugins").resolve("prebuilt_plugin_jars"))) {
                return checkout(directory, checkerFramework, recorder, java);
            }
        }
        throw new NotFoundException("Cannot find Arodnap's tools: set ARODNAP_HOME to the Arodnap installation directory.");
    }

    private static Installation distribution(Path home, Optional<Path> checkerFramework, Path recorder, Path java) throws NotFoundException {
        Path tools = home.resolve("tools");
        Path checker = checkerFramework.isPresent() ? checkerJar(checkerFramework.get()) : home.resolve("checker-framework").resolve("checker.jar");
        return new Installation(toolchain(checker, home.resolve("stubs"), tools.resolve("close-injector.jar"), tools.resolve("owning-field-fixer.jar"),
                tools.resolve("rlfixer.jar"), tools.resolve("rlpatcher.jar"), tools.resolve("field-transformations.jar"),
                tools.resolve("error_prone_core-2.50.0-with-dependencies.jar"), tools.resolve("error_prone_core-2.42.0-with-dependencies.jar"),
                tools.resolve("dataflow-errorprone-3.41.0-eisop1.jar")),
                new BuildRecording.Hooks(tools.resolve("ant-capture.jar"), tools.resolve("maven-capture.jar"), recorder, java));
    }

    private static Installation checkout(Path root, Optional<Path> checkerFramework, Path recorder, Path java) throws NotFoundException {
        Path jars = root.resolve("restructure_plugins").resolve("prebuilt_plugin_jars");
        Path checker = checkerFramework.isPresent() ? checkerJar(checkerFramework.get())
                : root.resolve("checker_framework/checker-framework-4.2.3/checker/dist/checker.jar");
        return new Installation(toolchain(checker, root.resolve("checker_framework").resolve("stubs"),
                jars.resolve("AutoCloseInjector-1.0-SNAPSHOT.jar"), jars.resolve("OwningFieldFixer-1.0-SNAPSHOT.jar"),
                jars.resolve("RLFixer-1.0-SNAPSHOT.jar"), jars.resolve("RLPatcher-1.0-SNAPSHOT.jar"), jars.resolve("arodnap-field-transformations.jar"),
                jars.resolve("error_prone_core-2.50.0-with-dependencies.jar"), jars.resolve("error_prone_core-2.42.0-with-dependencies.jar"),
                jars.resolve("dataflow-errorprone-3.41.0-eisop1.jar")),
                new BuildRecording.Hooks(jars.resolve("arodnap-ant-capture.jar"), jars.resolve("arodnap-maven-capture.jar"), recorder, java));
    }

    private static Toolchain toolchain(Path checkerJar, Path stubs, Path closeInjector, Path owningField, Path rlfixer, Path rlpatcher,
            Path fieldTransformations, Path errorProne, Path errorProneJdk17, Path dataflow) {
        return new Toolchain(checkerJar, stubs, closeInjector, owningField, rlfixer, rlpatcher, fieldTransformations, errorProne,
                errorProneJdk17, dataflow, checkerVersion(checkerJar), testedJdks(checkerJar));
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

    /** From the distribution's wpi.sh when there is one; the bundled release's list otherwise. */
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
        return checkerVersion(checkerJar).equals("4.2.3") ? BUNDLED_TESTED_JDKS : List.of();
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
