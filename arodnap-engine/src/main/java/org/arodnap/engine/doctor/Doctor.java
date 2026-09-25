package org.arodnap.engine.doctor;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.arodnap.engine.files.FilePaths;
import org.arodnap.engine.inputs.ProjectCapture;
import org.arodnap.engine.inputs.ProjectCapture.CapturedProject;
import org.arodnap.engine.inputs.UnsupportedProjectException;
import org.arodnap.engine.jdk.Jdk;
import org.arodnap.engine.jdk.JdkLocator;
import org.arodnap.engine.json.Json;
import org.arodnap.engine.pipeline.RunSettings;
import org.arodnap.engine.pipeline.Workspace;
import org.arodnap.engine.tools.CheckerFramework;
import org.arodnap.engine.tools.Toolchain;
import org.arodnap.model.BuildDescription;

/**
 * Checks that Arodnap can run on a project: the JDK, the tools, and whether the project's inputs
 * can be captured (on a copy). Writes {@code doctor.json}; its check names are stable output.
 */
public final class Doctor {
    /** A check's outcome. */
    public enum Status { OK, WARNING, ERROR }

    /** One check. */
    public record Check(String name, Status status, String message, Map<String, Object> details) {
        Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("name", name);
            json.put("status", status.name().toLowerCase(Locale.ROOT));
            json.put("message", message);
            if (details != null) {
                json.put("details", details);
            }
            return json;
        }
    }

    private final Toolchain toolchain;
    private final JdkLocator jdks;

    public Doctor(Toolchain toolchain, JdkLocator jdks) {
        this.toolchain = toolchain;
        this.jdks = jdks;
    }

    /** Runs every check, writes doctor.json and prints one line per check; returns whether none failed. */
    public boolean run(RunSettings settings, ProjectCapture capture, PrintStream out) throws IOException {
        List<Check> checks = new ArrayList<>();
        checks.add(javaRuntime());
        checks.add(checkerFrameworkPath());
        checks.add(checkerFrameworkTools());
        checks.add(pluginJars());
        Check repoPath = repoPath(settings.repoRoot());
        checks.add(repoPath);
        if (repoPath.status() == Status.OK) {
            Check unsupported = null;
            try {
                capture.detect(settings.repoRoot());
            } catch (UnsupportedProjectException e) {
                // No build Arodnap can capture: say so without copying the repository first.
                unsupported = new Check("adapter_selection", Status.ERROR, e.getMessage(), details("repo_root", settings.repoRoot().toString()));
            }
            if (unsupported != null) {
                checks.add(unsupported);
            } else {
                try (Workspace workspace = Workspace.copyOf(settings.repoRoot(), settings.keepWorkspace())) {
                    checks.addAll(repoChecks(settings, capture, workspace));
                } catch (IOException e) {
                    checks.add(new Check("workspace_copy", Status.ERROR, "Failed to create workspace copy for doctor: " + e.getMessage(),
                            details("repo_root", settings.repoRoot().toString())));
                }
            }
        }
        boolean success = checks.stream().noneMatch(check -> check.status() == Status.ERROR);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("repo_root", settings.repoRoot().toString());
        report.put("out_dir", settings.outDir().toString());
        report.put("build_args", settings.buildArgs());
        report.put("compile_target", settings.compileTarget().orElse(null));
        report.put("success", success);
        report.put("checks", checks.stream().map(Check::toJson).toList());
        Path file = Json.writeFile(settings.outDir().resolve("doctor.json"), report, true);
        for (Check check : checks) {
            out.println("[" + check.status().name() + "] " + check.name() + ": " + check.message());
        }
        out.println("Doctor report: " + file);
        return success;
    }

    private Check javaRuntime() {
        Jdk jdk;
        try {
            jdk = CheckerFramework.analysisJdk(toolchain, jdks);
        } catch (CheckerFramework.CheckerFrameworkException e) {
            return new Check("java_runtime", Status.ERROR, e.getMessage(), null);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("home", jdk.home().toString());
        details.put("major_version", jdk.majorVersion());
        details.put("source", jdk.source());
        if (toolchain.newestTestedJdk().isPresent() && jdk.majorVersion() > toolchain.newestTestedJdk().get()) {
            return new Check("java_runtime", Status.WARNING, "JDK " + jdk.majorVersion() + " at " + jdk.home()
                    + " is newer than the JDKs Checker Framework " + toolchain.checkerFrameworkVersion() + " is tested on (up to "
                    + toolchain.newestTestedJdk().get() + "). It may work; if analysis fails, use a tested JDK.", details);
        }
        return new Check("java_runtime", Status.OK, "JDK " + jdk.majorVersion() + " is available at " + jdk.home() + " (from " + jdk.source() + ").",
                details);
    }

    private Check checkerFrameworkPath() {
        Path directory = toolchain.checkerJar().toAbsolutePath().getParent();
        Map<String, Object> details = details("path", String.valueOf(directory));
        if (directory != null && Files.isDirectory(directory)) {
            return new Check("checker_framework_path", Status.OK, "Checker Framework " + toolchain.checkerFrameworkVersion() + " is present: "
                    + directory, details);
        }
        return new Check("checker_framework_path", Status.ERROR, "Checker Framework is missing: " + directory, details);
    }

    private Check checkerFrameworkTools() {
        Map<String, Object> details = details("checker_jar", toolchain.checkerJar().toString());
        if (Files.isRegularFile(toolchain.checkerJar())) {
            return new Check("checker_framework_tools", Status.OK, "Checker Framework tools are present.", details);
        }
        return new Check("checker_framework_tools", Status.ERROR, "Checker Framework tools are missing: checker_jar", details);
    }

    private Check pluginJars() {
        Map<String, Object> details = new LinkedHashMap<>();
        toolchain.files().forEach((name, path) -> details.put(name, String.valueOf(path)));
        details.put("stubs_directory", toolchain.stubsDirectory().toString());
        List<String> missing = toolchain.missing().stream().filter(name -> !name.equals("checker_jar")).toList();
        if (missing.isEmpty()) {
            return new Check("plugin_jars", Status.OK, "Required tool jars are present.", details);
        }
        return new Check("plugin_jars", Status.ERROR, "Required tool jars are missing: " + String.join(", ", missing), details);
    }

    private static Check repoPath(Path repoRoot) {
        Map<String, Object> details = details("repo_root", repoRoot.toString());
        if (!Files.exists(repoRoot)) {
            return new Check("repo_path", Status.ERROR, "Repository path does not exist: " + repoRoot, details);
        }
        if (!Files.isDirectory(repoRoot)) {
            return new Check("repo_path", Status.ERROR, "Repository path is not a directory: " + repoRoot, details);
        }
        return new Check("repo_path", Status.OK, "Repository path exists: " + repoRoot, details);
    }

    private static List<Check> repoChecks(RunSettings settings, ProjectCapture capture, Workspace workspace) {
        List<Check> checks = new ArrayList<>();
        Path repoRoot = settings.repoRoot();
        BuildDescription build;
        try {
            build = capture.detect(workspace.root());
        } catch (UnsupportedProjectException e) {
            checks.add(new Check("adapter_selection", Status.ERROR, inRepo(e.getMessage(), workspace), details("repo_root", repoRoot.toString())));
            return checks;
        }
        Map<String, Object> selection = new LinkedHashMap<>();
        selection.put("adapter_name", build.adapterName());
        selection.put("build_system", build.buildSystem());
        checks.add(new Check("adapter_selection", Status.OK,
                "Selected adapter " + build.adapterName() + " for build system " + build.buildSystem() + ".", selection));

        CapturedProject captured;
        try {
            captured = capture.capture(workspace.root(), workspace.stateDirectory());
        } catch (UnsupportedProjectException e) {
            checks.add(new Check("repo_support", Status.ERROR, inRepo(e.getMessage(), workspace), selection));
            return checks;
        }
        BuildDescription captureBuild = captured.build();
        int generated = captured.inputs().generatedSources().size();
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("adapter_name", captureBuild.adapterName());
        project.put("build_system", captureBuild.buildSystem());
        project.put("build_file", display(captureBuild.buildFile(), workspace));
        project.put("build_tool", captureBuild.buildTool());
        project.put("build_tool_source", captureBuild.buildToolSource());
        project.put("compile_target", captureBuild.compileTarget());
        project.put("source_root", display(captured.inputs().sourceRoot(), workspace));
        checks.add(new Check("repo_support", Status.OK, "Captured " + captured.inputs().units().size() + " compile unit(s) with "
                + captured.inputs().sources().size() + " source file(s)" + (generated > 0 ? " and " + generated + " generated" : "")
                + " from the " + captureBuild.buildSystem() + " build.", project));
        checks.add(new Check("source_root", Status.OK, "Analysis root: " + display(captured.inputs().sourceRoot(), workspace),
                details("source_root", display(captured.inputs().sourceRoot(), workspace))));
        String tool = captureBuild.buildTool().isEmpty() ? captureBuild.adapterName() : captureBuild.buildTool().get(0);
        Map<String, Object> compile = new LinkedHashMap<>();
        compile.put("compile_target", captureBuild.compileTarget());
        compile.put("build_tool", captureBuild.buildTool());
        compile.put("build_tool_source", captureBuild.buildToolSource());
        checks.add(new Check("compile_target", Status.OK, "The project's " + captureBuild.buildSystem() + " build compiled successfully ("
                + captureBuild.buildToolSource() + " " + tool + ").", compile));
        return checks;
    }

    /** A path in the copy, shown as the same path in the project. */
    private static String display(Path path, Workspace workspace) {
        return FilePaths.relativeTo(path, workspace.root()).map(relative -> workspace.repoRoot().resolve(relative).toString())
                .orElse(FilePaths.real(path).toString());
    }

    private static String inRepo(String message, Workspace workspace) {
        return message == null ? "" : message.replace(workspace.root().toString(), workspace.repoRoot().toString());
    }

    private static Map<String, Object> details(String key, Object value) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put(key, value);
        return details;
    }
}
