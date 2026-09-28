package org.arodnap.maven;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.arodnap.engine.jdk.JdkLocator;
import org.arodnap.engine.pipeline.Engine;
import org.arodnap.engine.pipeline.RunFailedException;
import org.arodnap.engine.pipeline.RunSettings;
import org.arodnap.engine.pipeline.RunSettings.FieldTransformationMode;
import org.arodnap.engine.pipeline.Timeouts;
import org.arodnap.engine.process.CommandRunner;
import org.arodnap.engine.tools.Toolchain;
import org.eclipse.aether.RepositorySystem;

/** What every Arodnap goal shares: the reactor, the output directory, the limits and the tools. */
abstract class ArodnapMojo extends AbstractMojo {
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    protected MavenSession session;

    @Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
    protected List<MavenProject> reactorProjects;

    @Parameter(defaultValue = "${plugin}", readonly = true, required = true)
    protected PluginDescriptor plugin;

    @Component
    protected RepositorySystem repositorySystem;

    /** Where reports, logs and the patch go. Defaults to {@code target/arodnap} of the top-level project. */
    @Parameter(property = "arodnap.outputDirectory")
    protected File outputDirectory;

    /** Keep the copy of the project Arodnap analyzes, for debugging. */
    @Parameter(property = "arodnap.keepWorkspace", defaultValue = "false")
    protected boolean keepWorkspace;

    /** Limit in seconds for each Checker Framework run and each compile of the analyzed program; none by default. */
    @Parameter(property = "arodnap.analysisTimeout")
    protected Integer analysisTimeout;

    /** Limit in seconds for each repair tool run; none by default. */
    @Parameter(property = "arodnap.stageTimeout")
    protected Integer stageTimeout;

    /** Skip Arodnap. */
    @Parameter(property = "arodnap.skip", defaultValue = "false")
    protected boolean skip;

    abstract String command();

    Path projectRoot() {
        return session.getTopLevelProject().getBasedir().toPath().toAbsolutePath().normalize();
    }

    Path output() {
        return outputDirectory != null ? outputDirectory.toPath().toAbsolutePath().normalize()
                : Path.of(session.getTopLevelProject().getBuild().getDirectory(), "arodnap");
    }

    FieldTransformationMode fieldTransformations() {
        return FieldTransformationMode.RESOURCES;
    }

    RunSettings settings() throws MojoExecutionException {
        return new RunSettings(command(), projectRoot(), output(), keepWorkspace,
                new Timeouts(Optional.empty(), seconds("analysisTimeout", analysisTimeout), seconds("stageTimeout", stageTimeout)),
                fieldTransformations(), List.of(), Optional.of("compile"));
    }

    Toolchain toolchain() throws MojoExecutionException {
        return MavenToolchain.resolve(repositorySystem, session.getRepositorySession(), session.getCurrentProject().getRemoteProjectRepositories(),
                output().resolve("tools"));
    }

    Engine engine(Toolchain toolchain, PrintStream out) {
        CommandRunner runner = CommandRunner.processes();
        return new Engine(toolchain, runner, JdkLocator.fromEnvironment(runner), out, (patches, repo) -> applyCommand());
    }

    ReactorCapture capture() {
        return new ReactorCapture(session, reactorProjects, projectRoot());
    }

    /** How to apply the patch from the command line: by prefix when the POM declares the plugin. */
    String applyCommand() {
        boolean declared = session.getTopLevelProject().getBuildPlugins().stream()
                .anyMatch(declaredPlugin -> declaredPlugin.getArtifactId().equals(plugin.getArtifactId()));
        return declared ? "mvn arodnap:apply" : "mvn " + plugin.getGroupId() + ":" + plugin.getArtifactId() + ":" + plugin.getVersion() + ":apply";
    }

    /** Engine output, line by line into Maven's log. */
    PrintStream log() {
        return new PrintStream(new ByteArrayOutputStream() {
            @Override
            public void flush() {
                String text = toString(StandardCharsets.UTF_8);
                int newline;
                while ((newline = text.indexOf('\n')) >= 0) {
                    getLog().info(text.substring(0, newline));
                    text = text.substring(newline + 1);
                }
                reset();
                byte[] rest = text.getBytes(StandardCharsets.UTF_8);
                write(rest, 0, rest.length);
            }
        }, true, StandardCharsets.UTF_8);
    }

    static MojoFailureException failure(RunFailedException e, Path report) {
        return new MojoFailureException("Arodnap failed: " + e.getMessage() + " (details in " + report + ")", e);
    }

    private static Optional<Duration> seconds(String name, Integer value) throws MojoExecutionException {
        if (value == null) {
            return Optional.empty();
        }
        if (value <= 0) {
            throw new MojoExecutionException(name + ": expected a positive number of seconds, got " + value);
        }
        return Optional.of(Duration.ofSeconds(value));
    }
}
