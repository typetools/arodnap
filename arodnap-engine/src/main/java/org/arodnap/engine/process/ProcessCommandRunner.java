package org.arodnap.engine.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Runs commands as processes. Output is read on separate threads so a chatty tool never blocks on a
 * full pipe. A command over its limit is killed together with every process it started (builds
 * leave daemons and compiler workers behind), and so is a command whose run is interrupted.
 */
final class ProcessCommandRunner implements CommandRunner {
    @Override
    public CommandResult run(Command command) throws CommandException {
        ProcessBuilder builder = new ProcessBuilder(command.arguments()).directory(command.workingDirectory().toFile());
        builder.environment().putAll(command.environment());
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new CommandException.NotStarted(command, e);
        }
        // The tools read no input.
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // nothing to close
        }
        CompletableFuture<String> stdout = drain(process.getInputStream());
        CompletableFuture<String> stderr = drain(process.getErrorStream());
        try {
            boolean finished;
            if (command.timeout().isPresent()) {
                Duration limit = command.timeout().get();
                finished = process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS);
                if (!finished) {
                    killTree(process);
                    throw new CommandException.TimedOut(command, limit, join(stdout), join(stderr));
                }
            } else {
                process.waitFor();
            }
            return new CommandResult(command, process.exitValue(), join(stdout), join(stderr));
        } catch (InterruptedException e) {
            killTree(process);
            Thread.currentThread().interrupt();
            throw new CommandException(command, "Interrupted while running: " + command.rendered(), e);
        }
    }

    private static CompletableFuture<String> drain(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                stream.transferTo(bytes);
                return bytes.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, runnable -> {
            Thread thread = new Thread(runnable, "arodnap-output");
            thread.setDaemon(true);
            thread.start();
        });
    }

    private static String join(CompletableFuture<String> output) throws InterruptedException {
        try {
            return output.get();
        } catch (ExecutionException e) {
            return "";
        }
    }

    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
