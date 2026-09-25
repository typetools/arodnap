package org.arodnap.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.arodnap.engine.pipeline.RunFailedException;

/** Runs whole-program inference, then the Resource Leak Checker, on every module. Run it after compiling. */
@Mojo(name = "infer", aggregator = true, requiresDependencyResolution = ResolutionScope.COMPILE, threadSafe = true)
public class InferMojo extends ArodnapMojo {
    @Override
    String command() {
        return "infer";
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping Arodnap (arodnap.skip).");
            return;
        }
        try {
            engine(toolchain(), log()).infer(settings(), capture());
            getLog().info("Report: " + output().resolve("report.json"));
        } catch (RunFailedException e) {
            throw failure(e, output().resolve("report.json"));
        }
    }
}
