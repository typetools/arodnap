package com.example;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Scanner;

/**
 * The {@code PatchFileGenerator} class provides functionality to generate
 * unified diff patch files between two versions of a file. This class ensures
 * that both the original and modified files end with a newline character before
 * generating the patch. It also filters out unnecessary whitespace changes in
 * the diff output.
 */
public class PatchFileGenerator {

    /**
     * Generates a unified diff patch between the original and modified files if the
     * modified file compiles successfully within the given project context.
     * If the modified file does not compile, an empty string is returned.
     * <p>
     * This method temporarily replaces the original file with the modified file,
     * attempts to compile the project, and then restores the original file. If the
     * compilation is successful, it generates and returns the unified diff patch
     * between the two files.
     * @param projectRoot      the root directory of the project
     * @param baselineLog      the path to the baseline compilation log file
     * @param originalFilePath the file path of the original version of the file
     * @param modifiedFilePath the file path of the modified version of the file
     * @return a string representing the unified diff patch if the modified file
     *         compiles successfully; otherwise, an empty string
     * @throws Exception if an error occurs during file operations or compilation
     */
    public static String generatePatchIfCompiles(
            String projectRoot,
            Path baselineLog,
            String originalFilePath,
            String modifiedFilePath) throws Exception {

        // 1) Temporarily swap modified -> original, compile, restore
        Path original = Paths.get(originalFilePath);
        Path backup = Paths.get(originalFilePath + ".bak_autoclose");

        Files.copy(original, backup, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(Paths.get(modifiedFilePath), original, StandardCopyOption.REPLACE_EXISTING);

        Path modifiedLog = Files.createTempFile("autoclose-modified-", ".log");
        try {
            CompilerUtils.compileAndCapture(projectRoot, modifiedLog.toString());
        } finally {
            // Always restore original
            Files.move(backup, original, StandardCopyOption.REPLACE_EXISTING);
        }

        boolean ok = !CompilerUtils.outputsDiffer(baselineLog, modifiedLog);
        Files.deleteIfExists(modifiedLog);

        if (!ok) {
            return "";
        }

        // 2) If compile ok, generate diff
        return generatePatch(originalFilePath, modifiedFilePath);
    }

    /**
     * Generates a unified diff patch between the original and modified versions of
     * a file.
     * This method ensures that both files have a newline at the end before
     * generating the patch,
     * and filters out lines that are only whitespace changes.
     *
     * @param originalFilePath the file path of the original version of the file.
     * @param modifiedFilePath the file path of the modified version of the file.
     * @return a string representing the unified diff patch between the two files.
     * @throws IOException if an I/O error occurs while reading the files or
     *                     generating the patch.
     */
    public static String generatePatch(String originalFilePath, String modifiedFilePath) throws IOException {
        // Ensure newline at end of both files. This is necessary for the patch tool
        // while applying the diffs. The patch tool complains if the files do not end
        // with a newline.
        ensureNewlineAtEnd(originalFilePath);
        ensureNewlineAtEnd(modifiedFilePath);

        ProcessBuilder processBuilder = new ProcessBuilder("diff", "-u", "-w", "-B", originalFilePath,
                modifiedFilePath);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        try (Scanner scanner = new Scanner(process.getInputStream())) {
            StringBuilder patch = new StringBuilder();
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                // Ignore lines that are just whitespace changes
                // if (!line.trim().isEmpty()) {
                    patch.append(line).append("\n");
                // }
            }
            return patch.toString().replace(modifiedFilePath, originalFilePath);
        }
    }

    /**
     * Ensures that the specified file ends with a newline character. If the file
     * does not already end with a newline, one is added.
     *
     * @param filePath the file path of the file to be checked and possibly
     *                 modified.
     * @throws IOException if an I/O error occurs while reading or writing to the
     *                     file.
     */
    public static void ensureNewlineAtEnd(String filePath) throws IOException {
        File file = new File(filePath);
        String content = new String(Files.readAllBytes(file.toPath()));
        if (!content.endsWith("\n")) {
            try (FileWriter writer = new FileWriter(file, true)) {
                writer.write("\n");
            }
        }
    }
}
