package org.arodnap.maven;

import java.io.IOException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.arodnap.engine.doctor.Doctor;
import org.arodnap.engine.jdk.JdkLocator;
import org.arodnap.engine.process.CommandRunner;

/** Checks that Arodnap can run on the build: the JDK, the tools, and every module's compilation. */
@Mojo(name = "doctor", aggregator = true, requiresDependencyResolution = ResolutionScope.COMPILE, threadSafe = true)
public class DoctorMojo extends ArodnapMojo {
    @Override
    String command() {
        return "doctor";
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            boolean ok = new Doctor(toolchain(), JdkLocator.fromEnvironment(CommandRunner.processes())).run(settings(), capture(), log());
            if (!ok) {
                throw new MojoFailureException("Arodnap's checks failed; see " + output().resolve("doctor.json"));
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Arodnap's checks could not run: " + e.getMessage(), e);
        }
    }
}
