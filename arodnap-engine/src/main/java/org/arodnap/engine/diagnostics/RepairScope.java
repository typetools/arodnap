package org.arodnap.engine.diagnostics;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.arodnap.engine.files.FilePaths;

/**
 * Which of the checker's warnings the repair stages get. Not the ones in code its developers marked
 * as intended ({@link Suppressions}), and not the ones in files the build generates, which are
 * analyzed but never changed. The report gives those leaks the reason this class gives them.
 */
public final class RepairScope {
    public static final String SUPPRESSED = "suppressed";
    public static final String GENERATED = "generated";

    /** A warning the repair stages do not get; {@code file} is relative to the workspace, as in {@link Diagnostic}. */
    public record Withheld(String file, int line, String reason) {}

    private final Suppressions suppressions;
    private final Set<Path> generated;

    private RepairScope(Suppressions suppressions, Set<Path> generated) {
        this.suppressions = suppressions;
        this.generated = generated;
    }

    /** The scope for this output of the checker, given the sources the build generated. */
    public static RepairScope of(String diagnostics, Collection<Path> generatedSources, Charset encoding) throws IOException {
        Set<Path> generated = new HashSet<>();
        generatedSources.forEach(source -> generated.add(FilePaths.real(source)));
        Set<Path> files = Suppressions.filesWithWarnings(diagnostics);
        files.removeIf(file -> generated.contains(FilePaths.real(file)));
        return new RepairScope(Suppressions.find(files, encoding), generated);
    }

    /** Why the repair stages do not get this warning, if they do not. */
    public Optional<String> withheld(CheckerWarning warning) {
        Path file = Path.of(warning.file());
        if (generated.contains(FilePaths.real(file))) {
            return Optional.of(GENERATED);
        }
        return suppressions.covers(file, warning.line()) ? Optional.of(SUPPRESSED) : Optional.empty();
    }

    /** The checker's output without the withheld warnings: what the repair stages work on. */
    public String repairable(String diagnostics) {
        StringBuilder kept = new StringBuilder();
        int from = 0;
        for (CheckerWarning.Block block : CheckerWarning.blocks(diagnostics)) {
            if (withheld(block.warning()).isPresent()) {
                kept.append(diagnostics, from, block.start());
                from = block.end();
            }
        }
        return kept.append(diagnostics.substring(from)).toString();
    }

    /** The withheld warnings of this output, with why. */
    public List<Withheld> withheldWarnings(String diagnostics, Path workspaceRoot) {
        Path root = Diagnostic.realPath(workspaceRoot);
        List<Withheld> withheld = new ArrayList<>();
        for (CheckerWarning warning : CheckerWarning.parseAll(diagnostics)) {
            withheld(warning).ifPresent(reason -> withheld.add(new Withheld(Diagnostic.relative(warning.file(), root), warning.line(), reason)));
        }
        return withheld;
    }
}
