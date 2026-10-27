package org.arodnap.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.arodnap.engine.files.FilePaths;
import org.arodnap.model.CompileUnit;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * A Maven module's main compilation as a compile unit, read from the project model and the
 * maven-compiler-plugin's configuration: the same facts the compiler plugin passes to javac.
 */
final class MavenCompileUnits {
    private static final String COMPILER = "org.apache.maven.plugins:maven-compiler-plugin";
    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{([^}]+)}");

    private MavenCompileUnits() {}

    /**
     * The module's compile units: its main compilation, plus one per source root the build generated
     * (under the build directory, e.g. {@code target/generated-sources/...}), whose sources are
     * analyzed but never patched. Empty for a module without Java sources (a POM, for example).
     */
    static List<CompileUnit> of(MavenProject project, MavenSession session) throws IOException, DependencyResolutionRequiredException {
        Path buildDirectory = FilePaths.real(Path.of(project.getBuild().getDirectory()));
        List<Path> sources = new ArrayList<>();
        List<Path> generatedRoots = new ArrayList<>();
        for (String root : project.getCompileSourceRoots()) {
            Path directory = Path.of(root);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            if (FilePaths.real(directory).startsWith(buildDirectory)) {
                generatedRoots.add(directory);
                continue;
            }
            try (Stream<Path> files = Files.walk(directory)) {
                files.filter(file -> file.toString().endsWith(".java") && Files.isRegularFile(file)).sorted(FilePaths.BY_NAMES).forEach(sources::add);
            }
        }
        if (sources.isEmpty()) {
            return List.of();
        }
        Xpp3Dom configuration = compilerConfiguration(project);
        Optional<Integer> release = level(value(configuration, "release", project, session)
                .or(() -> property("maven.compiler.release", project, session)))
                .or(() -> level(value(configuration, "source", project, session).or(() -> property("maven.compiler.source", project, session))));
        Optional<String> encoding = value(configuration, "encoding", project, session)
                .or(() -> property("project.build.sourceEncoding", project, session));
        Path generated = value(configuration, "generatedSourcesDirectory", project, session).map(Path::of)
                .orElse(Path.of(project.getBuild().getDirectory(), "generated-sources", "annotations"));
        List<Path> classpath = project.getCompileClasspathElements().stream().map(Path::of).toList();
        Optional<String> label = Optional.of(project.getGroupId() + ":" + project.getArtifactId());
        List<CompileUnit> units = new ArrayList<>();
        units.add(new CompileUnit(project.getBasedir().toPath(), sources, classpath, Optional.of(Path.of(project.getBuild().getOutputDirectory())),
                Optional.of(generated), release, encoding, List.of(), List.of(), label));
        for (Path root : generatedRoots) {
            if (!FilePaths.real(root).equals(FilePaths.real(generated))) {
                units.add(new CompileUnit(project.getBasedir().toPath(), List.of(), List.of(), Optional.empty(), Optional.of(root), release,
                        encoding, List.of(), List.of(), label));
            }
        }
        return units;
    }

    /** The compiler plugin's configuration for the default compile execution, over its plugin-level configuration. */
    private static Xpp3Dom compilerConfiguration(MavenProject project) {
        for (Plugin plugin : project.getBuildPlugins()) {
            if (!(plugin.getGroupId() + ":" + plugin.getArtifactId()).equals(COMPILER)) {
                continue;
            }
            Xpp3Dom configuration = plugin.getConfiguration() instanceof Xpp3Dom dom ? new Xpp3Dom(dom) : new Xpp3Dom("configuration");
            for (PluginExecution execution : plugin.getExecutions()) {
                if (execution.getId().equals("default-compile") && execution.getConfiguration() instanceof Xpp3Dom dom) {
                    configuration = Xpp3Dom.mergeXpp3Dom(new Xpp3Dom(dom), configuration);
                }
            }
            return configuration;
        }
        return new Xpp3Dom("configuration");
    }

    private static Optional<String> value(Xpp3Dom configuration, String name, MavenProject project, MavenSession session) {
        Xpp3Dom child = configuration.getChild(name);
        if (child == null || child.getValue() == null || child.getValue().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(resolve(child.getValue().strip(), project, session)).filter(value -> !value.isEmpty() && !value.contains("${"));
    }

    private static Optional<String> property(String name, MavenProject project, MavenSession session) {
        String value = session.getUserProperties().getProperty(name);
        if (value == null) {
            value = project.getProperties().getProperty(name);
        }
        if (value == null) {
            value = session.getSystemProperties().getProperty(name);
        }
        return Optional.ofNullable(value).map(text -> resolve(text, project, session)).filter(text -> !text.isEmpty() && !text.contains("${"));
    }

    /** Resolves {@code ${...}} against the project's and session's properties and a few project values. */
    static String resolve(String text, MavenProject project, MavenSession session) {
        String result = text;
        for (int round = 0; round < 10 && result.contains("${"); round++) {
            Matcher matcher = EXPRESSION.matcher(result);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                String name = matcher.group(1);
                String value = switch (name) {
                    case "project.build.directory" -> project.getBuild().getDirectory();
                    case "project.build.outputDirectory" -> project.getBuild().getOutputDirectory();
                    case "project.basedir", "basedir" -> project.getBasedir().getPath();
                    case "project.version" -> project.getVersion();
                    default -> {
                        String property = session.getUserProperties().getProperty(name);
                        if (property == null) {
                            property = project.getProperties().getProperty(name);
                        }
                        if (property == null) {
                            property = session.getSystemProperties().getProperty(name);
                        }
                        yield property;
                    }
                };
                matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? matcher.group() : value));
            }
            matcher.appendTail(out);
            if (out.toString().equals(result)) {
                break;
            }
            result = out.toString();
        }
        return result;
    }

    private static Optional<Integer> level(Optional<String> value) {
        return value.flatMap(text -> {
            String level = text.strip().startsWith("1.") ? text.strip().substring(2) : text.strip();
            return level.chars().allMatch(Character::isDigit) && !level.isEmpty() ? Optional.of(Integer.parseInt(level)) : Optional.empty();
        });
    }
}
