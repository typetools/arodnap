package com.rlc.fixer;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * The {@code CompilerUtils} class provides helper methods to compile Java
 * projects
 * and capture the compiler output.
 * <p>
 * It is used to check whether modified source files still compile cleanly by
 * comparing
 * the compilation logs before and after applying patches.
 */
public class CompilerUtils {

    /**
     * Compiles all Java source files in the given project and writes the compiler
     * output to a log file.
     *
     * @param projectRoot the root directory of the project
     * @param logPath     the path to write the compilation log
     * @return a list of strings representing the captured compiler output
     * @throws IOException          if an I/O error occurs during file or process
     *                              operations
     * @throws InterruptedException if the compilation process is interrupted
     */
    public static List<String> compileAndCapture(String projectRoot, String logPath)
            throws IOException, InterruptedException {
        String srcListPath = projectRoot + "/src-files.txt";
        String libPath = projectRoot + "/lib";
        String compiledOut = projectRoot + "/compiled_classes";
        Files.createDirectories(Paths.get(compiledOut));

        new ProcessBuilder("bash", "-c", "find " + projectRoot + "/src -name \"*.java\" > " + srcListPath)
                .inheritIO().start().waitFor();

        ProcessBuilder pb = new ProcessBuilder("javac", "-g", "-d", compiledOut, "-cp", libPath, "@" + srcListPath);
        pb.redirectErrorStream(true);

        Process proc = pb.start();
        List<String> output = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.add(line);
            }
        }

        Files.write(Paths.get(logPath), output);
        proc.waitFor();
        return output;
    }

    /**
     * Compares the contents of two log files to determine if they differ.
     *
     * @param path1 the path to the first log file
     * @param path2 the path to the second log file
     * @return {@code true} if the contents differ; {@code false} if they are
     *         identical
     * @throws IOException if an I/O error occurs while reading the files
     */
    public static boolean outputsDiffer(Path path1, Path path2) throws IOException {
        return !Files.readAllLines(path1).equals(Files.readAllLines(path2));
    }
}