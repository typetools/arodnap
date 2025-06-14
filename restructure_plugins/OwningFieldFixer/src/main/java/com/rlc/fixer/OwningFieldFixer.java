package com.rlc.fixer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * The {@code OwningFieldFixer} class automates the detection and repair
 * of owning field reassignment issues in Java projects.
 * <p>
 * It processes log files produced by the Resource Leak Checker (RLC).
 * If a log entry indicates an owning field reassignment, the tool attempts
 * to make the field {@code private} and/or {@code final}, applies the change,
 * and verifies the patch by recompiling the project.
 * Only changes that compile successfully are kept.
 */
public class OwningFieldFixer {
    public static Path PATCH_FILE;

    public static void main(String[] args) throws Exception {
        if (args.length < 4 || !args[0].equals("--log") || !args[2].equals("--project-root")) {
            System.err.println(
                    "Usage: java -jar OwningFieldFixer-1.0-SNAPSHOT.jar --log inf-log.txt --project-root /path/to/project");
            System.exit(1);
        }

        String logPath = args[1];
        String projectRoot = args[3];

        PATCH_FILE = Paths.get(projectRoot, "src", "owning-field.patch");
        if (PATCH_FILE.toFile().exists()) {
            PATCH_FILE.toFile().delete();
        }

        List<LogEntry> entries = LogParser.parse(logPath);
        if (entries.isEmpty()) {
            System.out.println("No entries found in the log file.");
            return;
        }

        Path baselineLog = Paths.get(projectRoot, "baseline.log");
        CompilerUtils.compileAndCapture(projectRoot, baselineLog.toString());

        for (LogEntry entry : entries) {
            PatchGenerator.process(entry, projectRoot, baselineLog);
        }
    }
}