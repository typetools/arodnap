package org.arodnap.engine.inputs;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.arodnap.model.CompileUnit;

/** Reads a recorded javac command line into a {@link CompileUnit}. */
public final class JavacArguments {
    /** javac options whose value is the next argument (or follows "=" for the long forms). */
    private static final Set<String> OPTIONS_WITH_VALUE = Set.of(
            "-d", "-s", "-h",
            "-cp", "-classpath", "--class-path",
            "-sourcepath", "--source-path",
            "-processorpath", "--processor-path", "--processor-module-path",
            "-processor",
            "-encoding",
            "--release", "-source", "--source", "-target", "--target",
            "-bootclasspath", "--boot-class-path", "-extdirs", "--extension-dirs", "-endorseddirs",
            "--system", "--module-path", "-p", "--module-source-path", "--upgrade-module-path",
            "--add-modules", "--limit-modules", "--add-exports", "--add-reads", "--patch-module",
            "--module", "-m", "--module-version", "-profile",
            "-Xmaxerrs", "-Xmaxwarns", "--default-module-for-created-files");

    private JavacArguments() {}

    /** The compile unit of one javac command line, or empty if it compiles no Java sources. */
    public static Optional<CompileUnit> parse(List<String> arguments, Path workingDirectory, Optional<String> label) {
        Map<String, String> values = new HashMap<>();
        List<Path> sources = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            int equals = argument.indexOf('=');
            if (argument.startsWith("--") && equals > 0 && OPTIONS_WITH_VALUE.contains(argument.substring(0, equals))) {
                values.put(argument.substring(0, equals), argument.substring(equals + 1));
            } else if (OPTIONS_WITH_VALUE.contains(argument) && i + 1 < arguments.size()) {
                values.put(argument, arguments.get(++i));
            } else if (!argument.startsWith("-") && argument.endsWith(".java")) {
                sources.add(resolve(argument, workingDirectory));
            }
        }
        if (sources.isEmpty()) {
            return Optional.empty();
        }
        String processors = values.getOrDefault("-processor", "");
        return Optional.of(new CompileUnit(
                workingDirectory,
                sources,
                pathList(first(values, "-cp", "-classpath", "--class-path"), workingDirectory),
                Optional.ofNullable(values.get("-d")).filter(text -> !text.isEmpty()).map(text -> resolve(text, workingDirectory)),
                Optional.ofNullable(values.get("-s")).filter(text -> !text.isEmpty()).map(text -> resolve(text, workingDirectory)),
                javaLevel(first(values, "--release", "-source", "--source")),
                Optional.ofNullable(values.get("-encoding")),
                pathList(first(values, "-processorpath", "--processor-path"), workingDirectory),
                processors.isEmpty() ? List.of() : List.of(processors.split(",")).stream().filter(name -> !name.isEmpty()).toList(),
                label));
    }

    private static String first(Map<String, String> values, String... names) {
        for (String name : names) {
            if (values.containsKey(name)) {
                return values.get(name);
            }
        }
        return null;
    }

    static Path resolve(String text, Path workingDirectory) {
        Path path = Path.of(text);
        return path.isAbsolute() ? path : workingDirectory.resolve(path);
    }

    /** A class path; javac's "dir/*" means every jar in dir. */
    static List<Path> pathList(String value, Path workingDirectory) {
        List<Path> entries = new ArrayList<>();
        if (value == null) {
            return entries;
        }
        for (String part : value.split(File.pathSeparator, -1)) {
            if (part.isEmpty() || part.equals("\"\"") || part.equals("''")) {
                continue;
            }
            if (part.endsWith("/*") || part.endsWith(File.separator + "*")) {
                entries.addAll(jarsIn(resolve(part.substring(0, part.length() - 2), workingDirectory)));
            } else {
                entries.add(resolve(part, workingDirectory));
            }
        }
        return entries;
    }

    private static List<Path> jarsIn(Path directory) {
        List<Path> lower = new ArrayList<>();
        List<Path> upper = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.endsWith(".jar")) {
                    lower.add(entry);
                } else if (name.endsWith(".JAR")) {
                    upper.add(entry);
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        lower.sort(null);
        upper.sort(null);
        lower.addAll(upper);
        return lower;
    }

    static Optional<Integer> javaLevel(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String text = value.strip();
        if (text.startsWith("1.")) {
            text = text.substring(2);
        }
        return !text.isEmpty() && text.chars().allMatch(Character::isDigit) ? Optional.of(Integer.parseInt(text)) : Optional.empty();
    }
}
