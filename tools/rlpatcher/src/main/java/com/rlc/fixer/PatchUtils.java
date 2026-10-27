package com.rlc.fixer;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class PatchUtils {

    /** Returns a List<String> with a unified diff between original and modified. */
    public static List<String> diff(String original, String modified) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("diff", "-u", original, modified);
        Process p = pb.start();
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) out.add(line);
        }
        return out;
    }

    /** Append diff lines to PATCH_FILE, creating it if necessary. */
    public static void writePatch(Path patchFile, List<String> diffLines) throws IOException {
        if (!diffLines.isEmpty()) {
            Files.write(patchFile, diffLines,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }
}
