package org.arodnap.engine.stages;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Close injection: wrapper classes that own a resource but have no way to release it get a
 * {@code close()} method and implement {@code AutoCloseable} (AutoCloseInjector).
 */
public final class CloseInjectionStage extends PatchingToolStage {
    public static final String NAME = "close_injector";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Optional<String> reanalysisLabel() {
        return Optional.of("post_close_injector");
    }

    @Override
    List<String> command(StageContext context, List<String> javaProperties) {
        List<String> command = new ArrayList<>();
        command.add(context.run().jdk().java().toString());
        command.addAll(javaProperties);
        command.addAll(List.of("-jar", context.run().toolchain().closeInjectorJar().toString(),
                context.analysis().diagnostics().toString(), context.workspaceRoot().toString()));
        return command;
    }

    @Override
    String patchFileName() {
        return "close_injector.patch";
    }

    @Override
    String rawPatchFileName() {
        return "java-parser-AutoCloseInjector.patch";
    }

    @Override
    String logTitle() {
        return "close_injector_tool";
    }

    @Override
    String diagnosticsLabel() {
        return "close injector";
    }

    @Override
    String toolName() {
        return "Close injector";
    }
}
