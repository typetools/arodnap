package org.arodnap.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.apache.maven.plugin.MojoExecutionException;
import org.arodnap.engine.tools.Stubs;
import org.arodnap.engine.tools.ToolCoordinates;
import org.arodnap.engine.tools.Toolchain;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

/**
 * The tools, resolved through Maven like any other artifact (so mirrors, proxies and the local
 * repository apply). The Checker Framework's jars are laid out side by side as {@code checker.jar},
 * {@code checker-qual.jar} and {@code checker-util.jar}, which is how the checker finds them.
 */
final class MavenToolchain {
    private MavenToolchain() {}

    static Toolchain resolve(RepositorySystem system, RepositorySystemSession session, List<RemoteRepository> repositories, Path directory)
            throws MojoExecutionException {
        try {
            Path checker = Files.createDirectories(directory.resolve("checker-framework"));
            for (String name : List.of("checker", "checker-qual", "checker-util")) {
                Files.copy(resolve(system, session, repositories, name), checker.resolve(name + ".jar"), StandardCopyOption.REPLACE_EXISTING);
            }
            return new Toolchain(checker.resolve("checker.jar"), Stubs.extract(directory.resolve("stubs")),
                    resolve(system, session, repositories, "close-injector"), resolve(system, session, repositories, "owning-field-fixer"),
                    resolve(system, session, repositories, "rlfixer"), resolve(system, session, repositories, "rlpatcher"),
                    resolve(system, session, repositories, "field-transformations"), resolve(system, session, repositories, "error-prone"),
                    resolve(system, session, repositories, "error-prone-jdk17"), resolve(system, session, repositories, "dataflow"),
                    ToolCoordinates.checkerFrameworkVersion(), ToolCoordinates.CHECKER_FRAMEWORK_TESTED_JDKS);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot set up Arodnap's tools in " + directory + ": " + e.getMessage(), e);
        }
    }

    private static Path resolve(RepositorySystem system, RepositorySystemSession session, List<RemoteRepository> repositories, String tool)
            throws MojoExecutionException {
        String coordinates = ToolCoordinates.of(tool);
        String[] parts = coordinates.split(":");
        DefaultArtifact artifact = parts.length == 4 ? new DefaultArtifact(parts[0], parts[1], parts[2], "jar", parts[3])
                : new DefaultArtifact(parts[0], parts[1], "jar", parts[2]);
        try {
            return system.resolveArtifact(session, new ArtifactRequest(artifact, repositories, null)).getArtifact().getFile().toPath();
        } catch (ArtifactResolutionException e) {
            throw new MojoExecutionException("Cannot download Arodnap's " + tool + " (" + coordinates + "): " + e.getMessage(), e);
        }
    }
}
