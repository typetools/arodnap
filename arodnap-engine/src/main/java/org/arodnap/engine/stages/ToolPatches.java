package org.arodnap.engine.stages;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.arodnap.engine.files.FilePaths;

/**
 * Normalizes the unified diffs the repair tools write: both headers of each file name its path
 * relative to the workspace root. Tools label one side of a header pair with a temporary file
 * ({@code --- <workspace file>} / {@code +++ /tmp/patch-123.java}), so each pair resolves to
 * whichever path is in the workspace.
 */
public final class ToolPatches {
    private ToolPatches() {}

    /** A normalized patch and the files it changes, in order. */
    public record Normalized(String text, List<String> changedFiles) {}

    public static Normalized normalize(String rawPatch, Path workspaceRoot) throws StageException {
        // The tools write "\n"; a "\r" left from a Windows-style file ends the line, as the Python
        // version read the patch with newline translation. Lines split only at "\n".
        String text = rawPatch.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        Path root = FilePaths.real(workspaceRoot);
        LinkedHashSet<String> changed = new LinkedHashSet<>();
        List<String> normalized = new ArrayList<>();
        boolean sawHeader = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("--- ")) {
                if (i + 1 >= lines.size() || !lines.get(i + 1).startsWith("+++ ")) {
                    throw new StageException("Patch output did not contain a valid unified diff header pair.");
                }
                String[] old = header(line, "--- ");
                String[] updated = header(lines.get(i + 1), "+++ ");
                String target = target(old[0], updated[0], root);
                normalized.add("--- " + (old[0].equals("/dev/null") ? "/dev/null" : target) + old[1]);
                normalized.add("+++ " + (updated[0].equals("/dev/null") ? "/dev/null" : target) + updated[1]);
                changed.add(target);
                sawHeader = true;
                i++;
                continue;
            }
            if (line.startsWith("+++ ")) {
                throw new StageException("Patch output contained an unexpected unified diff header order.");
            }
            normalized.add(line);
        }
        if (!sawHeader) {
            throw new StageException("Patch output did not contain unified diff file headers.");
        }
        if (changed.isEmpty()) {
            throw new StageException("Patch output did not reference any repo files.");
        }
        return new Normalized(String.join("\n", normalized) + "\n", List.copyOf(changed));
    }

    /** The header's path and everything from the tab on (a timestamp), which is kept. */
    private static String[] header(String line, String prefix) {
        String payload = line.substring(prefix.length());
        int tab = payload.indexOf('\t');
        return tab < 0 ? new String[] {payload.strip(), ""} : new String[] {payload.substring(0, tab).strip(), payload.substring(tab)};
    }

    private static String target(String oldPath, String newPath, Path root) throws StageException {
        for (String candidate : new String[] {newPath, oldPath}) {
            Optional<String> relative = candidate(candidate, root);
            if (relative.isPresent()) {
                return relative.get();
            }
        }
        throw new StageException("Patch paths did not reference a file under workspace root " + root + ": " + oldPath + " -> " + newPath);
    }

    private static Optional<String> candidate(String pathText, Path root) throws StageException {
        if (pathText.equals("/dev/null")) {
            return Optional.empty();
        }
        Path path = Path.of(pathText);
        if (path.isAbsolute()) {
            return FilePaths.relativeTo(path, root);
        }
        String relative = pathText.startsWith("./") ? pathText.substring(2) : pathText;
        Path relativePath = Path.of(relative);
        for (Path name : relativePath) {
            if (name.toString().equals("..")) {
                throw new StageException("Unsupported patch path outside workspace root: " + pathText);
            }
        }
        if (relativePath.isAbsolute()) {
            throw new StageException("Unsupported patch path outside workspace root: " + pathText);
        }
        return Optional.of(relativePath.toString().replace('\\', '/'));
    }
}
