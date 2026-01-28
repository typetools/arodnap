package com.rlc.fixer;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class CompilerUtils {
    public static List<String> compile(String projectRoot) throws IOException, InterruptedException {
        String srcListPath = projectRoot + "/src-files.txt";
        String libPath = projectRoot + "/lib";
        String cp;
        if (!Files.exists(Paths.get(libPath))) {
            cp = projectRoot + "/lib"; // try 'libs' as alternative
        } else {
            cp = Files.list(Paths.get(libPath))
                    .filter(p -> p.toString().endsWith(".jar"))
                    .map(Path::toString)
                    .collect(Collectors.joining(":"));
            cp += ":" + libPath;
        }
        String compiledOut = projectRoot + "/compiled_classes";
        Files.createDirectories(Paths.get(compiledOut));

        // Collect all Java source files
        new ProcessBuilder("bash", "-c", "find " + projectRoot + "/src -name \"*.java\" > " + srcListPath)
                .inheritIO().start().waitFor();

        // Compile using javac
        ProcessBuilder pb = new ProcessBuilder("javac", "-g", "-d", compiledOut, "-cp", cp, "@" + srcListPath);
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
    }

    public static boolean outputsDiffer(List<String> baseline, List<String> patched) {
        // return !baseline.equals(patched);
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
