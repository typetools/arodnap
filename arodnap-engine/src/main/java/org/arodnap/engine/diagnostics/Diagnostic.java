package org.arodnap.engine.diagnostics;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A warning with its structured parts, as the leak report tracks it from one analysis to the next.
 *
 * @param path the file, relative to the workspace root when it is inside it
 * @param line the line number
 * @param key the Checker Framework key, e.g. {@code required.method.not.called}
 * @param fields the {@code -Adetailedmsgtext} arguments; empty for plain javac warnings
 * @param text the whole warning block
 */
public record Diagnostic(String path, int line, String key, List<String> fields, String text) {
    public static final String LEAK_KEY = "required.method.not.called";
    private static final Pattern KEY = Pattern.compile("warning: [(\\[](?:[\\w.]+:)?([\\w.]+)[)\\]]");
    private static final Pattern CAPTURE_NUMBER = Pattern.compile("capture#\\d+");

    public Diagnostic {
        fields = List.copyOf(fields);
    }

    /** Parses checker output; paths are made relative to {@code workspaceRoot} where possible. */
    public static List<Diagnostic> parseAll(String diagnostics, Path workspaceRoot) {
        Path root = realPath(workspaceRoot);
        List<Diagnostic> result = new ArrayList<>();
        for (CheckerWarning warning : CheckerWarning.parseAll(diagnostics)) {
            String firstLine = warning.firstLine();
            Matcher key = KEY.matcher(firstLine);
            if (!key.find()) {
                continue;
            }
            // "<location> [key] $$ N $$ arg1 $$ ... $$ argN $$ ( start, end ) $$ message"
            String[] parts = firstLine.split("\\$\\$", -1);
            List<String> fields = List.of();
            if (parts.length >= 3 && isDigits(parts[1].strip())) {
                int count = Integer.parseInt(parts[1].strip());
                List<String> collected = new ArrayList<>();
                for (int i = 2; i < Math.min(parts.length, 2 + count); i++) {
                    collected.add(parts[i].strip());
                }
                fields = collected;
            }
            result.add(new Diagnostic(relative(warning.file(), root), warning.line(), key.group(1), fields, warning.message()));
        }
        return result;
    }

    public boolean fromChecker() {
        return !fields.isEmpty();
    }

    public boolean isLeak() {
        return key.equals(LEAK_KEY);
    }

    /**
     * What makes two warnings in different analysis runs the same warning. Line numbers are left
     * out, because repairs shift lines; for a leak, the method, expression and type identify it (the
     * reason text changes when code around it changes). javac numbers captured wildcards
     * ({@code capture#68 of ? extends T}) anew in every compilation, so the numbers are left out too.
     */
    public List<String> identity() {
        List<String> identity = new ArrayList<>(List.of(path, key));
        if (isLeak() && fields.size() >= 3) {
            fields.subList(0, 3).forEach(field -> identity.add(withoutCaptureNumbers(field)));
        } else if (!fields.isEmpty()) {
            fields.forEach(field -> identity.add(withoutCaptureNumbers(field)));
        } else {
            String first = text.lines().findFirst().orElse("");
            String marker = "[" + key + "]";
            int at = first.indexOf(marker);
            identity.add(withoutCaptureNumbers((at < 0 ? first : first.substring(at + marker.length())).strip()));
        }
        return identity;
    }

    private static String withoutCaptureNumbers(String text) {
        return CAPTURE_NUMBER.matcher(text).replaceAll("capture#");
    }

    private static boolean isDigits(String text) {
        return !text.isEmpty() && text.chars().allMatch(Character::isDigit);
    }

    static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static String relative(String file, Path root) {
        Path path = realPath(Path.of(file));
        return path.startsWith(root) ? root.relativize(path).toString().replace('\\', '/') : file;
    }
}
