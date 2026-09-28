package org.arodnap.capture;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.taskdefs.Javac;
import org.apache.tools.ant.taskdefs.compilers.Javac13;

/**
 * An Ant compiler adapter that records every javac invocation, then compiles exactly as Ant's
 * standard in-process adapter does.
 *
 * <p>Used as {@code ant -lib arodnap-ant-capture.jar
 * -Dbuild.compiler=org.arodnap.capture.RecordingJavacAdapter}. Each invocation is appended to
 * the file named by {@code ARODNAP_CAPTURE_FILE} as a JSON line {@code {"cwd": ..., "args": [...]}}.
 */
public class RecordingJavacAdapter extends Javac13 {

    @Override
    public boolean execute() throws BuildException {
        String captureFile = System.getenv("ARODNAP_CAPTURE_FILE");
        if (captureFile != null) {
            record(captureFile, capturedArguments());
        }
        return super.execute();
    }

    private List<String> capturedArguments() {
        List<String> args = new ArrayList<>(Arrays.asList(setupModernJavacCommand().getArguments()));
        // Ant only emits the language level for adapters it knows target Java 9+, so add it
        // from the task attributes when it is missing.
        Javac task = getJavac();
        boolean hasLevel = args.contains("--release") || args.contains("-source");
        if (!hasLevel && task.getRelease() != null && !task.getRelease().isEmpty()) {
            args.add(0, task.getRelease());
            args.add(0, "--release");
        } else if (!hasLevel && task.getSource() != null && !task.getSource().isEmpty()) {
            args.add(0, task.getSource());
            args.add(0, "-source");
        }
        return args;
    }

    private void record(String captureFile, List<String> args) {
        StringBuilder json = new StringBuilder("{\"cwd\": ")
                .append(quote(getProject().getBaseDir().getAbsolutePath()))
                .append(", \"args\": [");
        for (int i = 0; i < args.size(); i++) {
            json.append(i == 0 ? "" : ", ").append(quote(args.get(i)));
        }
        json.append("]}\n");
        try (Writer out = new FileWriter(captureFile, true)) {
            out.write(json.toString());
        } catch (IOException e) {
            throw new BuildException("Arodnap could not record the javac invocation in " + captureFile, e);
        }
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
