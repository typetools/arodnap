package com.rlc.fixer;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * The {@code PatchGenerator} class handles generating and validating field
 * modification patches
 * based on RLC log entries.
 * <p>
 * For each detected owning field reassignment, it tries making the field
 * {@code private},
 * {@code final}, or both, verifies the change via recompilation, and records
 * only successful patches.
 */
public class PatchGenerator {

    private static final Set<String> processed = new HashSet<>();

    /**
     * Processes a single log entry by attempting a series of patch strategies and
     * validating them.
     *
     * @param entry       the log entry describing the owning field reassignment
     * @param projectRoot the root directory of the Java project
     * @param baselineLog the baseline compilation log for comparison
     * @throws Exception if an I/O or compilation error occurs
     */
    public static void process(LogEntry entry, String projectRoot, Path baselineLog) throws Exception {
        String key = entry.file + ":" + entry.field;
        if (processed.contains(key))
            return;

        Path tempFile = Files.createTempFile("patch-", ".java");

        // Try final + private
        if (attemptPatch(entry, projectRoot, baselineLog, tempFile, true, true)) {
            processed.add(key);
            Files.deleteIfExists(tempFile);
            return;
        }

        // Try final only
        Files.copy(Paths.get(entry.file), tempFile, StandardCopyOption.REPLACE_EXISTING);
        if (attemptPatch(entry, projectRoot, baselineLog, tempFile, true, false)) {
            processed.add(key);
            Files.deleteIfExists(tempFile);
            return;
        }

        // Try private only
        Files.copy(Paths.get(entry.file), tempFile, StandardCopyOption.REPLACE_EXISTING);
        if (attemptPatch(entry, projectRoot, baselineLog, tempFile, false, true)) {
            processed.add(key);
        }

        Files.deleteIfExists(tempFile);
    }

    /**
     * Attempts to modify a field's modifiers and verifies if the change passes
     * compilation.
     *
     * @param entry       the log entry
     * @param projectRoot the project root directory
     * @param baselineLog the baseline log file
     * @param tempFile    the temporary file for patching
     * @param makeFinal   whether to attempt adding {@code final}
     * @param makePrivate whether to attempt adding {@code private}
     * @return {@code true} if the patch is successful and compiles cleanly;
     *         {@code false} otherwise
     * @throws Exception if an error occurs during modification or compilation
     */
    private static boolean attemptPatch(LogEntry entry, String projectRoot, Path baselineLog, Path tempFile,
            boolean makeFinal, boolean makePrivate) throws Exception {
        Files.copy(Paths.get(entry.file), tempFile, StandardCopyOption.REPLACE_EXISTING);
        boolean changed = FieldModifier.tryModifyField(tempFile.toString(), entry.field, makeFinal, makePrivate);
        if (!changed)
            return false;

        Path original = Paths.get(entry.file);
        Path backup = Paths.get(entry.file + ".bak");

        Files.copy(original, backup, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(tempFile, original, StandardCopyOption.REPLACE_EXISTING);

        Path modifiedLog = Files.createTempFile("modified-", ".log");
        CompilerUtils.compileAndCapture(projectRoot, modifiedLog.toString());
        Files.move(backup, original, StandardCopyOption.REPLACE_EXISTING);

        if (!CompilerUtils.outputsDiffer(baselineLog, modifiedLog)) {
            writePatch(entry, tempFile);
            return true;
        }

        return false;
    }

    /**
     * Writes the generated unified diff patch to the central patch file.
     *
     * @param entry        the log entry being patched
     * @param modifiedFile the modified source file
     * @throws IOException if an I/O error occurs while writing the patch
     */
    private static void writePatch(LogEntry entry, Path modifiedFile) throws IOException {
        List<String> diff = generateUnifiedDiff(entry.file, modifiedFile.toString());
        if (!diff.isEmpty()) {
            Files.write(OwningFieldFixer.PATCH_FILE, diff, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    /**
     * Generates a unified diff between the original and modified files.
     *
     * @param original the path to the original file
     * @param modified the path to the modified file
     * @return a list of diff lines
     * @throws IOException if an I/O error occurs during diff generation
     */
    public static List<String> generateUnifiedDiff(String original, String modified) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("diff", "-u", original, modified);
        Process process = pb.start();
        List<String> diffLines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                diffLines.add(line);
            }
        }
        return diffLines;
    }
}
