package com.rlc.fixer;

import java.nio.file.*;
import java.util.*;

/**
 * The {@code LogParser} class reads and parses RLC log files to extract
 * entries related to non-final owning field reassignments.
 * <p>
 * It identifies log lines containing reassignment warnings and collects
 * the affected file and field names for further processing.
 */
public class LogParser {

    /**
     * Parses the given log file and extracts unique owning field reassignment
     * entries.
     *
     * @param logPath the path to the log file
     * @return a list of {@code LogEntry} objects representing fields to process
     * @throws Exception if an I/O error occurs while reading the log file
     */
    public static List<LogEntry> parse(String logPath) throws Exception {
        List<String> lines = Files.readAllLines(Paths.get(logPath));
        List<LogEntry> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < lines.size() - 1; i++) {
            String line = lines.get(i);
            if (line.contains("Non-final owning field might be overwritten")) {
                int colon1 = line.indexOf(':');
                int colon2 = line.indexOf(':', colon1 + 1);
                if (colon1 != -1 && colon2 != -1) {
                    String file = line.substring(0, colon1).trim();
                    String nextLine = lines.get(i + 1).trim();

                    String field = null;
                    if (nextLine.contains("=")) {
                        String lhs = nextLine.split("=")[0].trim();
                        if (lhs.startsWith("this.")) {
                            field = lhs.substring("this.".length());
                        } else if (!lhs.contains(".")) {
                            field = lhs;
                        }
                    }

                    if (field != null) {
                        String key = file + ":" + field;
                        if (!seen.contains(key)) {
                            seen.add(key);
                            results.add(new LogEntry(file, field));
                        }
                    }
                }
            }
        }

        return results;
    }
}
