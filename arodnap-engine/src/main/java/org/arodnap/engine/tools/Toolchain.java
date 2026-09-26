package org.arodnap.engine.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.arodnap.engine.jdk.Jdk;

/**
 * Where the tools Arodnap runs are. The engine only runs them; each front end says where they are
 * (the CLI from its distribution, the Maven and Gradle plugins from resolved artifacts).
 *
 * @param checkerJar the Checker Framework's {@code checker.jar}; {@code checker-qual.jar} and
 *     {@code checker-util.jar} must be next to it unless it contains their classes
 * @param stubsDirectory Arodnap's stub files for the Resource Leak Checker
 * @param closeInjectorJar AutoCloseInjector
 * @param owningFieldFixerJar OwningFieldFixer
 * @param rlfixerJar RLFixer
 * @param rlpatcherJar RLPatcher
 * @param fieldTransformationsJar the Error Prone checks that make resource fields final or local
 * @param errorProneJar the latest Error Prone (with dependencies), used on JDK 21 and newer
 * @param errorProneJdk17Jar the last Error Prone that runs on JDK 17, used on JDK 17 to 20
 * @param dataflowJar the dataflow library both Error Prone releases use
 * @param checkerFrameworkVersion the Checker Framework version, for reports
 * @param testedJdks JDKs the Checker Framework release is tested on, if known; doctor warns about newer ones
 */
public record Toolchain(
        Path checkerJar,
        Path stubsDirectory,
        Path closeInjectorJar,
        Path owningFieldFixerJar,
        Path rlfixerJar,
        Path rlpatcherJar,
        Path fieldTransformationsJar,
        Path errorProneJar,
        Path errorProneJdk17Jar,
        Path dataflowJar,
        String checkerFrameworkVersion,
        List<Integer> testedJdks) {

    /** A tool's jar, by its name in {@link ToolCoordinates}: how a front end gets the tools. */
    @FunctionalInterface
    public interface Jars<E extends Exception> {
        Path jar(String tool) throws E;
    }

    /**
     * The toolchain of this Arodnap release from its tools' artifacts (resolved by a build plugin).
     * The Checker Framework's jars are copied side by side into {@code directory/checker-framework}
     * as {@code checker.jar}, {@code checker-qual.jar} and {@code checker-util.jar}, which is how
     * the checker finds them; the stubs are extracted into {@code directory/stubs}.
     */
    public static <E extends Exception> Toolchain fromArtifacts(Jars<E> jars, Path directory) throws E, IOException {
        Path checker = Files.createDirectories(directory.resolve("checker-framework"));
        for (String name : List.of("checker", "checker-qual", "checker-util")) {
            Files.copy(jars.jar(name), checker.resolve(name + ".jar"), StandardCopyOption.REPLACE_EXISTING);
        }
        return new Toolchain(checker.resolve("checker.jar"), Stubs.extract(directory.resolve("stubs")), jars.jar("close-injector"),
                jars.jar("owning-field-fixer"), jars.jar("rlfixer"), jars.jar("rlpatcher"), jars.jar("field-transformations"),
                jars.jar("error-prone"), jars.jar("error-prone-jdk17"), jars.jar("dataflow"), ToolCoordinates.checkerFrameworkVersion(),
                ToolCoordinates.CHECKER_FRAMEWORK_TESTED_JDKS);
    }

    /** Error Prone releases from 2.43 need JDK 21 to run; 2.42.0 is the last one that runs on 17. */
    public static final int LATEST_ERROR_PRONE_MINIMUM_JDK = 21;

    public Toolchain {
        Objects.requireNonNull(checkerJar, "checkerJar");
        Objects.requireNonNull(stubsDirectory, "stubsDirectory");
        testedJdks = List.copyOf(testedJdks);
    }

    /** The Error Prone jar that runs on this JDK, and the javac flags it needs there. */
    public ErrorProne errorProneFor(Jdk jdk) {
        if (jdk.majorVersion() >= LATEST_ERROR_PRONE_MINIMUM_JDK) {
            // Required by Error Prone 2.46+ on JDK 21 (JDK-8225377); harmless on newer JDKs.
            return new ErrorProne(errorProneJar, List.of("-XDaddTypeAnnotationsToSymbol=true"));
        }
        return new ErrorProne(errorProneJdk17Jar, List.of());
    }

    /** An Error Prone jar and the extra javac flags it needs. */
    public record ErrorProne(Path jar, List<String> javacFlags) {}

    /** Every file the pipeline needs, by name, for doctor and for failing early. */
    public Map<String, Path> files() {
        Map<String, Path> files = new LinkedHashMap<>();
        files.put("checker_jar", checkerJar);
        files.put("close_injector_jar", closeInjectorJar);
        files.put("owning_field_jar", owningFieldFixerJar);
        files.put("rlfixer_jar", rlfixerJar);
        files.put("rlpatcher_jar", rlpatcherJar);
        files.put("field_transformations_jar", fieldTransformationsJar);
        files.put("error_prone_jar", errorProneJar);
        files.put("error_prone_jdk17_jar", errorProneJdk17Jar);
        files.put("dataflow_jar", dataflowJar);
        return files;
    }

    /** Names of the files in {@link #files()} that are missing, sorted. */
    public List<String> missing() {
        List<String> missing = new java.util.ArrayList<>(files().entrySet().stream()
                .filter(entry -> entry.getValue() == null || !Files.isRegularFile(entry.getValue()))
                .map(Map.Entry::getKey).toList());
        if (!Files.isDirectory(stubsDirectory)) {
            missing.add("stubs_directory");
        }
        missing.sort(null);
        return missing;
    }

    /** The newest tested JDK, if the release lists them. */
    public Optional<Integer> newestTestedJdk() {
        return testedJdks.stream().max(Integer::compare);
    }
}
