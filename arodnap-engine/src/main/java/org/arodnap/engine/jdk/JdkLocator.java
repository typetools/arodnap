package org.arodnap.engine.jdk;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.arodnap.engine.process.Command;
import org.arodnap.engine.process.CommandException;
import org.arodnap.engine.process.CommandResult;
import org.arodnap.engine.process.CommandRunner;

/** Finds the JDK to use: {@code JAVA_HOME} if set, else the {@code java} on {@code PATH}. */
public final class JdkLocator {
    private static final Pattern JAVA_HOME_PROPERTY = Pattern.compile("^\\s*java\\.home\\s*=\\s*(.+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern VERSION_PROPERTY =
            Pattern.compile("^\\s*java\\.specification\\.version\\s*=\\s*(\\S+)\\s*$", Pattern.MULTILINE);

    private final CommandRunner runner;
    private final Map<String, String> environment;

    public JdkLocator(CommandRunner runner, Map<String, String> environment) {
        this.runner = runner;
        this.environment = Map.copyOf(environment);
    }

    /** A locator for this process's environment. */
    public static JdkLocator fromEnvironment(CommandRunner runner) {
        return new JdkLocator(runner, System.getenv());
    }

    /** Thrown when no usable JDK is found. */
    public static final class JdkNotFoundException extends Exception {
        private static final long serialVersionUID = 1L;

        public JdkNotFoundException(String message) {
            super(message);
        }
    }

    public Jdk locate() throws JdkNotFoundException {
        String javaHome = environment.get("JAVA_HOME");
        if (javaHome != null && !javaHome.isEmpty()) {
            Path java = Path.of(javaHome, "bin", "java");
            if (!Files.isRegularFile(java)) {
                throw new JdkNotFoundException("JAVA_HOME does not point at a JDK (missing " + java + ").");
            }
            Jdk described = describe(java, "JAVA_HOME");
            return new Jdk(Path.of(javaHome), described.majorVersion(), described.source());
        }
        Optional<Path> onPath = findOnPath("java");
        if (onPath.isEmpty()) {
            throw new JdkNotFoundException("No JDK found. Install a JDK or set JAVA_HOME.");
        }
        return describe(onPath.get(), "PATH");
    }

    /** The first executable of that name on {@code PATH}. */
    public Optional<Path> findOnPath(String program) {
        String path = environment.getOrDefault("PATH", "");
        for (String directory : path.split(File.pathSeparator)) {
            if (directory.isEmpty()) {
                continue;
            }
            Path candidate = Path.of(directory, program);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private Jdk describe(Path java, String source) throws JdkNotFoundException {
        CommandResult result;
        try {
            result = runner.run(Command.of(List.of(java.toString(), "-XshowSettings:properties", "-version"), Path.of(".").toAbsolutePath()));
        } catch (CommandException e) {
            throw new JdkNotFoundException("Could not run " + java + ": " + e.getMessage());
        }
        // -XshowSettings writes to stderr.
        String output = result.stderr() + result.stdout();
        Matcher home = JAVA_HOME_PROPERTY.matcher(output);
        Matcher version = VERSION_PROPERTY.matcher(output);
        if (!result.succeeded() || !home.find() || !version.find()) {
            throw new JdkNotFoundException("Could not determine the JDK behind " + java + ".");
        }
        Path jdkHome = Path.of(home.group(1));
        // Java 8 reports the embedded JRE; the JDK is its parent.
        if (jdkHome.getFileName() != null && jdkHome.getFileName().toString().equals("jre")
                && Files.isRegularFile(jdkHome.getParent().resolve("bin").resolve("javac"))) {
            jdkHome = jdkHome.getParent();
        }
        return new Jdk(jdkHome, majorVersion(version.group(1)), source);
    }

    static int majorVersion(String specificationVersion) {
        String[] parts = specificationVersion.split("\\.");
        return Integer.parseInt(specificationVersion.startsWith("1.") ? parts[1] : parts[0]);
    }
}
