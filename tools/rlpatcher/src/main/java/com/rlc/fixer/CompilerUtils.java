package com.rlc.fixer;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The {@code CompilerUtils} class provides helper methods to compile Java
 * projects and capture the compiler output.
 * <p>
 * It is used to check whether a patched project still compiles with the same
 * number of errors as the unpatched baseline.
 * <p>
 * Compile inputs come from the build when Arodnap provides them:
 * <ul>
 * <li>{@code -Darodnap.sourcesFile=<file>}: newline-separated absolute source paths</li>
 * <li>{@code -Darodnap.classpathFile=<file>}: newline-separated classpath entries</li>
 * </ul>
 * Without them, the legacy normalized layout ({@code src/} and {@code lib/}) is used.
 * Scratch files are written to a temporary directory, never into the project.
 */
public class CompilerUtils {

    static final String SOURCES_FILE_PROPERTY = "arodnap.sourcesFile";
    static final String CLASSPATH_FILE_PROPERTY = "arodnap.classpathFile";
    // The build's --release level and source encoding, when Arodnap passes them.
    static final String RELEASE_PROPERTY = "arodnap.release";
    static final String ENCODING_PROPERTY = "arodnap.encoding";

    public static List<String> compile(String projectRoot) throws IOException, InterruptedException {
        Path workDir = Files.createTempDirectory("arodnap-compile-");
        try {
            Path srcList = workDir.resolve("sources.txt");
            Files.write(srcList, sourceFiles(projectRoot).stream()
                    .map(CompilerUtils::quoteArgFileEntry)
                    .collect(Collectors.toList()));
            Path compiledOut = Files.createDirectories(workDir.resolve("classes"));

            List<String> command = new ArrayList<>(List.of(javacExecutable(), "-g", "-d", compiledOut.toString(),
                    "-cp", classpath(projectRoot)));
            command.addAll(languageOptions());
            command.add("@" + srcList);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            Process proc = pb.start();
            List<String> output = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.add(line);
                }
            }
            proc.waitFor();
            return output;
        } finally {
            deleteRecursively(workDir);
        }
    }

    static List<String> languageOptions() {
        List<String> options = new ArrayList<>();
        String release = System.getProperty(RELEASE_PROPERTY);
        if (release != null && !release.isBlank()) {
            options.add("--release");
            options.add(release);
        }
        String encoding = System.getProperty(ENCODING_PROPERTY);
        if (encoding != null && !encoding.isBlank()) {
            options.add("-encoding");
            options.add(encoding);
        }
        return options;
    }

    private static List<String> sourceFiles(String projectRoot) throws IOException {
        String sourcesFile = System.getProperty(SOURCES_FILE_PROPERTY);
        if (sourcesFile != null) {
            return nonEmptyLines(Paths.get(sourcesFile));
        }
        Path srcRoot = Paths.get(projectRoot, "src");
        if (!Files.isDirectory(srcRoot)) {
            return Collections.emptyList();
        }
        try (Stream<Path> paths = Files.walk(srcRoot)) {
            return paths.filter(p -> p.toString().endsWith(".java"))
                    .map(Path::toString)
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static String classpath(String projectRoot) throws IOException {
        String classpathFile = System.getProperty(CLASSPATH_FILE_PROPERTY);
        if (classpathFile != null) {
            return String.join(File.pathSeparator, nonEmptyLines(Paths.get(classpathFile)));
        }
        Path libPath = Paths.get(projectRoot, "lib");
        if (!Files.isDirectory(libPath)) {
            return libPath.toString();
        }
        try (Stream<Path> paths = Files.list(libPath)) {
            List<String> entries = paths.filter(p -> p.toString().endsWith(".jar"))
                    .map(Path::toString)
                    .sorted()
                    .collect(Collectors.toList());
            entries.add(libPath.toString());
            return String.join(File.pathSeparator, entries);
        }
    }

    /** Prefer the javac of the JDK running this tool over whatever is on PATH. */
    private static String javacExecutable() {
        Path javac = Paths.get(System.getProperty("java.home"), "bin", "javac");
        return Files.isExecutable(javac) ? javac.toString() : "javac";
    }

    private static List<String> nonEmptyLines(Path file) throws IOException {
        return Files.readAllLines(file).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .collect(Collectors.toList());
    }

    private static String quoteArgFileEntry(String path) {
        return "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        }
    }

    public static boolean outputsDiffer(List<String> baseline, List<String> patched) {
        Pattern errorLine = Pattern.compile("^[^:]+:\\d+: error: .*");
        int baselineErrors = 0, patchedErrors = 0;

        for (String line : baseline) {
            if (errorLine.matcher(line).matches())
                baselineErrors++;
        }
        for (String line : patched) {
            if (errorLine.matcher(line).matches())
                patchedErrors++;
        }

        return baselineErrors != patchedErrors;
    }
}
