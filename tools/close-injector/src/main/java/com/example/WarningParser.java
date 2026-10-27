package com.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code WarningParser} class is responsible for parsing warning messages
 * from a list of strings, extracting relevant details such as the file path,
 * line number, and method name from each warning message.
 * These extracted details are encapsulated in the {@code Warning} inner class,
 * which stores this information.
 */
public class WarningParser {

    /**
     * The {@code Warning} class is a data structure that encapsulates the details
     * of a warning message.
     * It stores the file path, line number, and method name extracted from a
     * warning message.
     */
    public static class Warning {
        public String filePath;
        public int lineNumber;
        public String methodName;

        public Warning(String filePath, int lineNumber, String methodName) {
            this.filePath = filePath;
            this.lineNumber = lineNumber;
            this.methodName = methodName;
        }

        @Override
        public String toString() {
            return "Warning{" +
                    "filePath='" + filePath + '\'' +
                    ", lineNumber=" + lineNumber +
                    ", methodName='" + methodName + '\'' +
                    '}';
        }
    }

    /**
     * Parses a list of warning messages, extracting the file path, line number, and
     * method name from each message. The extracted details are stored in
     * {@code Warning} objects, which are returned as a list.
     *
     * @param warningMessages the list of warning messages to be parsed.
     * @return a list of {@code Warning} objects containing the extracted details
     *         from the warning messages.
     */
    public List<Warning> parseWarnings(List<String> warningMessages) {
        List<Warning> warnings = new ArrayList<>();
        // Regular expression pattern to match warning messages
        Pattern pattern = Pattern.compile(
                "^(.*):(\\d+): warning: .* method (\\w+) .* regular method exit.*$");

        for (String message : warningMessages) {
            Matcher matcher = pattern.matcher(message);
            if (message.contains("src/org/jsl/collider/SessionImpl.java:976") ||
                    message.contains("src/org/maltparser/ml/liblinear/Liblinear.java:438") ||
                    message.contains("src/martin/gui/quantum/Item.java:74") ||
                    message.contains("src/org/maltparser/ml/libsvm/Libsvm.java:387") ||
                    message.contains("src/grammaticalbehaviors/GEBT_Mario/GEBT_MarioAgent.java:186") ||
                    message.contains("src/grammaticalbehaviors/GEBT_Mario/GEBT_MarioAgent.java:194") ||
                    message.contains("src/com/miguel/sxl/SXL.java:27") ||
                    message.contains("src/ikrs/httpd/resource/RangedResource.java:113") ||
                    message.contains("src/grinder/client/ClientGUI.java")) {
                // Bugs in RLC, and some peculiar cases generate patches with compilation
                // errors.
                // We skip these warnings to avoid unnecessary complications.
                continue;
            }
            if (matcher.find()) {
                String filePath = matcher.group(1);
                int lineNumber = Integer.parseInt(matcher.group(2));
                String methodName = matcher.group(3);
                warnings.add(new Warning(filePath, lineNumber, methodName));
            }
        }
        return warnings;
    }

    /**
     * Writes the parsed warnings to a JSON file.
     *
     * @param warnings     the list of parsed warnings to write to the file.
     * @param outputFolder the folder where the JSON file will be written.
     * @param projectPath  the path to the project directory containing the source
     *                     code.
     */
    public static void writeWarningsToFile(List<Warning> warnings, String outputFolder, String projectPath) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String jsonOutput = gson.toJson(warnings);
        String projectName = projectPath.substring(projectPath.lastIndexOf(File.separator) + 1);

        try (FileWriter writer = new FileWriter(Paths.get(outputFolder, projectName + ".json").toFile())) {
            writer.write(jsonOutput);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
