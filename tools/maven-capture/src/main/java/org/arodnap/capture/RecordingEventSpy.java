package org.arodnap.capture;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * A Maven core extension that records the inputs of every main compilation as javac arguments.
 *
 * <p>Loaded with {@code -Dmaven.ext.class.path=arodnap-maven-capture.jar}. After each successful
 * {@code maven-compiler-plugin:compile} execution it appends a JSON line
 * {@code {"cwd": ..., "task": ..., "args": [...]}} to the file named by {@code ARODNAP_CAPTURE_FILE}.
 * It reads the project model and the compiler plugin's configuration, so it works whether or not
 * the compiler forks and whatever the POM configures.
 */
@Named("arodnap-capture")
@Singleton
public class RecordingEventSpy extends AbstractEventSpy {

    @Override
    public void onEvent(Object event) throws Exception {
        if (!(event instanceof ExecutionEvent)) {
            return;
        }
        ExecutionEvent execution = (ExecutionEvent) event;
        MojoExecution mojo = execution.getMojoExecution();
        String captureFile = System.getenv("ARODNAP_CAPTURE_FILE");
        if (execution.getType() != ExecutionEvent.Type.MojoSucceeded
                || captureFile == null
                || mojo == null
                || !"maven-compiler-plugin".equals(mojo.getArtifactId())
                || !"compile".equals(mojo.getGoal())) {
            return;
        }
        record(captureFile, execution.getSession(), execution.getProject(), mojo);
    }

    private void record(String captureFile, MavenSession session, MavenProject project, MojoExecution mojo)
            throws IOException, ExpressionEvaluationException {
        Parameters parameters = new Parameters(session, mojo);
        if ("true".equals(parameters.value("skipMain"))) {
            return;
        }
        List<String> args = new ArrayList<>();
        args.add("-d");
        args.add(project.getBuild().getOutputDirectory());
        args.add("-classpath");
        try {
            args.add(String.join(File.pathSeparator, project.getCompileClasspathElements()));
        } catch (Exception e) {
            throw new IOException("Could not resolve the compile classpath of " + project.getId(), e);
        }
        addOption(args, "--release", parameters.value("release"));
        if (!args.contains("--release")) {
            addOption(args, "-source", parameters.value("source"));
            addOption(args, "-target", parameters.value("target"));
        }
        addOption(args, "-encoding", parameters.value("encoding"));
        addOption(args, "-s", parameters.value("generatedSourcesDirectory"));
        args.addAll(parameters.values("compilerArgs"));
        for (String root : project.getCompileSourceRoots()) {
            args.addAll(javaFiles(Paths.get(root)));
        }

        StringBuilder json = new StringBuilder("{\"cwd\": ")
                .append(quote(project.getBasedir().getAbsolutePath()))
                .append(", \"task\": ")
                .append(quote(project.getArtifactId() + ":" + mojo.getExecutionId()))
                .append(", \"args\": [");
        for (int i = 0; i < args.size(); i++) {
            json.append(i == 0 ? "" : ", ").append(quote(args.get(i)));
        }
        json.append("]}\n");
        synchronized (RecordingEventSpy.class) {
            try (Writer out = new FileWriter(captureFile, true)) {
                out.write(json.toString());
            }
        }
    }

    private static void addOption(List<String> args, String option, String value) {
        if (value != null && !value.isEmpty()) {
            args.add(option);
            args.add(value);
        }
    }

    private static List<String> javaFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return new ArrayList<>();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))
                    .map(p -> p.toAbsolutePath().toString())
                    .sorted()
                    .collect(Collectors.toList());
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

    /** The compiler plugin's configured parameters, with ${...} expressions evaluated. */
    private static final class Parameters {
        private final Xpp3Dom configuration;
        private final PluginParameterExpressionEvaluator evaluator;

        Parameters(MavenSession session, MojoExecution mojo) {
            this.configuration = mojo.getConfiguration();
            this.evaluator = new PluginParameterExpressionEvaluator(session, mojo);
        }

        String value(String name) throws ExpressionEvaluationException {
            Xpp3Dom child = configuration == null ? null : configuration.getChild(name);
            return child == null ? null : evaluate(child);
        }

        List<String> values(String name) throws ExpressionEvaluationException {
            List<String> result = new ArrayList<>();
            Xpp3Dom child = configuration == null ? null : configuration.getChild(name);
            if (child != null) {
                for (Xpp3Dom item : child.getChildren()) {
                    String value = evaluate(item);
                    if (value != null && !value.isEmpty()) {
                        result.add(value);
                    }
                }
            }
            return result;
        }

        private String evaluate(Xpp3Dom node) throws ExpressionEvaluationException {
            String raw = node.getValue() != null ? node.getValue() : node.getAttribute("default-value");
            if (raw == null) {
                return null;
            }
            Object value = evaluator.evaluate(raw.trim());
            return value == null ? null : value.toString();
        }
    }
}
