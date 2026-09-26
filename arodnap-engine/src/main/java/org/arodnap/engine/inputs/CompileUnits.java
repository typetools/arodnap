package org.arodnap.engine.inputs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.arodnap.engine.files.FilePaths;
import org.arodnap.model.CompileUnit;
import org.arodnap.model.ProjectInputs;

/**
 * Turns a build's compile units into the one program Arodnap analyzes: every unit's sources
 * compiled together, on the union of their class paths.
 */
public final class CompileUnits {
    /** A JDK 17+ javac accepts --release 8 and newer; older levels are analyzed as 8. */
    static final int OLDEST_RELEASE = 8;
    private static final List<String> LOMBOK_MARKERS = List.of("/org/projectlombok/", "/org.projectlombok/");

    private CompileUnits() {}

    /**
     * Reads a capture file: one JSON line {@code {"cwd": ..., "args": [...], "task": ...}} per
     * javac invocation. Identical lines are read once; invocations without sources are skipped.
     */
    public static List<CompileUnit> load(Path captureFile) throws IOException {
        List<CompileUnit> units = new ArrayList<>();
        if (!Files.isRegularFile(captureFile)) {
            return units;
        }
        ObjectMapper json = new ObjectMapper();
        Set<String> seen = new HashSet<>();
        for (String line : Files.readAllLines(captureFile, StandardCharsets.UTF_8)) {
            if (line.isBlank() || !seen.add(line)) {
                continue;
            }
            JsonNode record = json.readTree(line);
            List<String> arguments = new ArrayList<>();
            record.get("args").forEach(argument -> arguments.add(argument.asText()));
            Optional<String> label = Optional.ofNullable(record.get("task")).filter(node -> !node.isNull()).map(JsonNode::asText);
            JavacArguments.parse(arguments, Path.of(record.get("cwd").asText()), label).ifPresent(units::add);
        }
        return units;
    }

    /**
     * Merges units into one analysis universe.
     *
     * <p>Class path entries that hold only classes the units themselves compiled (a unit's output
     * directory, a sibling module's jar) are dropped: those classes come from the sources being
     * analyzed. Every other entry is kept, including dependency jars the build downloaded into the
     * project or checked into {@code lib/}.
     */
    public static ProjectInputs merge(List<CompileUnit> units) throws UnsupportedProjectException {
        if (units.isEmpty()) {
            throw new UnsupportedProjectException(
                    "The build compiled no Java sources, so there is nothing to analyze. Check that the build "
                            + "command compiles the project's main sources (and cleans first, so javac actually runs).");
        }
        // module-info.java is left out so every unit's sources compile together on the class path.
        LinkedHashSet<Path> sources = new LinkedHashSet<>();
        for (CompileUnit unit : units) {
            for (Path source : unit.sources()) {
                if (!isModuleInfo(source)) {
                    sources.add(FilePaths.real(source));
                }
            }
        }
        LinkedHashSet<Path> generated = new LinkedHashSet<>();
        for (CompileUnit unit : units) {
            Optional<Path> directory = unit.generatedSourceDirectory().filter(Files::isDirectory);
            if (directory.isEmpty()) {
                continue;
            }
            for (Path file : javaFilesUnder(directory.get())) {
                Path resolved = FilePaths.real(file);
                if (!sources.contains(resolved) && !isModuleInfo(file)) {
                    generated.add(resolved);
                }
            }
        }

        Set<Path> outputs = new HashSet<>();
        units.forEach(unit -> unit.outputDirectory().ifPresent(output -> outputs.add(FilePaths.real(output))));
        Set<String> compiledClasses = new HashSet<>();
        for (Path output : outputs) {
            compiledClasses.addAll(classEntries(output));
        }
        LinkedHashSet<Path> classpath = new LinkedHashSet<>();
        for (CompileUnit unit : units) {
            for (Path entry : unit.classpath()) {
                if (!Files.exists(entry)) {
                    continue;
                }
                Path resolved = FilePaths.real(entry);
                if (!isBuildArtifact(resolved, outputs, compiledClasses)) {
                    classpath.add(resolved);
                }
            }
        }

        List<Path> lombokCandidates = new ArrayList<>(classpath);
        units.forEach(unit -> lombokCandidates.addAll(unit.processorPath()));
        for (Path entry : lombokCandidates) {
            String posix = "/" + entry.toString().replace('\\', '/');
            String name = entry.getFileName() == null ? "" : entry.getFileName().toString();
            if (LOMBOK_MARKERS.stream().anyMatch(posix::contains) || name.startsWith("lombok")) {
                throw new UnsupportedProjectException("The build uses Lombok (" + entry + "), which rewrites code during "
                        + "compilation in a way Arodnap cannot reproduce from the sources. Lombok projects are not supported yet.");
            }
        }

        Optional<Integer> release = units.stream().flatMap(unit -> unit.release().stream()).max(Integer::compare)
                .map(level -> Math.max(level, OLDEST_RELEASE));
        Optional<String> encoding = units.stream().flatMap(unit -> unit.encoding().stream()).filter(name -> !name.isEmpty()).findFirst();
        List<Path> all = new ArrayList<>(sources);
        all.addAll(generated);
        return new ProjectInputs(units, List.copyOf(sources), List.copyOf(generated), List.copyOf(classpath), release, encoding,
                analysisRoot(all));
    }

    /**
     * The directory RLFixer treats as its project directory: the nearest common directory of all
     * sources, moved up while any source's relative path would start with {@code src/}, because
     * RLFixer strips that prefix from its source list.
     */
    public static Path analysisRoot(Collection<Path> sources) {
        Path root = null;
        for (Path source : sources) {
            Path parent = source.getParent();
            root = root == null ? parent : commonAncestor(root, parent);
        }
        if (root == null) {
            throw new IllegalArgumentException("No sources.");
        }
        while (root.getParent() != null && startsWithSrc(sources, root)) {
            root = root.getParent();
        }
        return root;
    }

    private static boolean startsWithSrc(Collection<Path> sources, Path root) {
        for (Path source : sources) {
            Path relative = root.relativize(source);
            if (relative.getNameCount() > 0 && relative.getName(0).toString().equals("src")) {
                return true;
            }
        }
        return false;
    }

    private static Path commonAncestor(Path a, Path b) {
        Path common = a.getRoot();
        int count = Math.min(a.getNameCount(), b.getNameCount());
        for (int i = 0; i < count && a.getName(i).equals(b.getName(i)); i++) {
            common = common == null ? a.getName(i) : common.resolve(a.getName(i));
        }
        return common;
    }

    private static boolean isBuildArtifact(Path entry, Set<Path> outputs, Set<String> compiledClasses) {
        if (outputs.contains(entry)) {
            return true;
        }
        Set<String> classes = classEntries(entry);
        return !classes.isEmpty() && compiledClasses.containsAll(classes);
    }

    /** Class files in a class path directory or jar, as archive-style relative paths ("a/b/C.class"). */
    static Set<String> classEntries(Path entry) {
        Set<String> classes = new HashSet<>();
        if (Files.isDirectory(entry)) {
            try (Stream<Path> files = Files.walk(entry)) {
                files.filter(file -> file.toString().endsWith(".class") && Files.isRegularFile(file))
                        .forEach(file -> classes.add(entry.relativize(file).toString().replace('\\', '/')));
            } catch (IOException e) {
                return Set.of();
            }
            return classes;
        }
        try (ZipFile zip = new ZipFile(entry.toFile())) {
            zip.stream().map(ZipEntry::getName).filter(name -> name.endsWith(".class")).forEach(classes::add);
        } catch (IOException e) {
            return Set.of();
        }
        return classes;
    }

    private static List<Path> javaFilesUnder(Path directory) {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(file -> file.toString().endsWith(".java") && Files.isRegularFile(file))
                    .sorted(FilePaths.BY_NAMES).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean isModuleInfo(Path source) {
        return source.getFileName() != null && source.getFileName().toString().equals("module-info.java");
    }
}
