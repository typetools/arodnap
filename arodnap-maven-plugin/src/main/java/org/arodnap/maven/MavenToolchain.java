package org.arodnap.maven;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.MojoExecutionException;
import org.arodnap.engine.tools.ToolCoordinates;
import org.arodnap.engine.tools.Toolchain;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

/** The tools, resolved through Maven like any other artifact (so mirrors, proxies and the local repository apply). */
final class MavenToolchain {
    private MavenToolchain() {}

    static Toolchain resolve(RepositorySystem system, RepositorySystemSession session, List<RemoteRepository> repositories, Path directory)
            throws MojoExecutionException {
        try {
            return Toolchain.fromArtifacts(tool -> resolve(system, session, repositories, tool), directory);
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
