package org.arodnap.engine.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.arodnap.engine.Version;
import org.arodnap.engine.patch.PatchApplier;
import org.arodnap.engine.process.Command;
import org.arodnap.engine.process.CommandResult;

/** Applies patches to a directory and logs each application the way stage logs record commands. */
public final class PatchLog {
    public static final String APPLIER = "arodnap built-in";
    public static final String FLAVOR = "builtin";

    private PatchLog() {}

    /** Applies {@code patchFile} under {@code root} and appends the outcome to {@code log}. */
    public static PatchApplier.Outcome apply(Path root, Path patchFile, PatchApplier.Options options, Path log, String title)
            throws IOException {
        String patchText = org.arodnap.engine.patch.Lines.read(patchFile);
        PatchApplier.Outcome outcome = PatchApplier.apply(root, patchText, options);
        append(log, title, root, patchFile, options, outcome);
        return outcome;
    }

    /** The version the logs and reports name for the built-in applier. */
    public static String version() {
        return "arodnap " + Version.VERSION;
    }

    private static void append(Path log, String title, Path root, Path patchFile, PatchApplier.Options options,
            PatchApplier.Outcome outcome) throws IOException {
        List<String> command = new ArrayList<>(List.of("arodnap-patch", "-p" + options.stripLevel()));
        if (options.checkOnly()) {
            command.add("--dry-run");
        }
        if (options.fuzz() > 0) {
            command.add("--fuzz=" + options.fuzz());
        }
        if (options.ignoreWhitespace()) {
            command.add("--ignore-whitespace");
        }
        command.add(patchFile.toString());
        CommandResult result = new CommandResult(Command.of(command, root), outcome.ok() ? 0 : 1,
                lines(outcome.messages()), lines(outcome.errors()));
        String text = String.join("\n", "== " + title + " ==", "PATCH_BINARY: " + APPLIER, "PATCH_FLAVOR: " + FLAVOR,
                "PATCH_VERSION: " + version(), result.log(Optional.of("patch")), "");
        Files.createDirectories(log.toAbsolutePath().getParent());
        Files.writeString(log, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String lines(List<String> lines) {
        StringBuilder text = new StringBuilder();
        lines.forEach(line -> text.append(line).append('\n'));
        return text.toString();
    }
}
