package org.arodnap.engine.stages;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Owning-field repair: an owning field that is overwritten gets its old value released first, and
 * its ownership made explicit (OwningFieldFixer).
 */
public final class OwningFieldStage extends PatchingToolStage {
    public static final String NAME = "owning_field";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Optional<String> reanalysisLabel() {
        return Optional.of("post_owning_field");
    }

    @Override
    List<String> command(StageContext context, List<String> javaProperties) {
        List<String> command = new ArrayList<>();
        command.add(context.run().jdk().java().toString());
        command.addAll(javaProperties);
        command.addAll(List.of("-jar", context.run().toolchain().owningFieldFixerJar().toString(),
                "--log", context.analysis().diagnostics().toString(), "--project-root", context.workspaceRoot().toString()));
        return command;
    }

    /** OwningFieldFixer's hunks can carry context that close injection already changed. */
    @Override
    int fuzz() {
        return 3;
    }

    @Override
    String patchFileName() {
        return "owning_field.patch";
    }

    @Override
    String rawPatchFileName() {
        return "owning-field.patch";
    }

    @Override
    String logTitle() {
        return "owning_field_tool";
    }

    @Override
    String diagnosticsLabel() {
        return "owning field";
    }

    @Override
    String toolName() {
        return "Owning field fixer";
    }
}
