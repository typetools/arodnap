package org.arodnap.engine.pipeline;

/**
 * A run failed. The message says why (an unsupported project, a failed analysis, a crashed tool);
 * report.json has the details. Front ends print the message without a stack trace.
 */
public class RunFailedException extends Exception {
    private static final long serialVersionUID = 1L;

    public RunFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
