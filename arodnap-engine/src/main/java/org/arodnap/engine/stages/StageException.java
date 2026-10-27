package org.arodnap.engine.stages;

/** A stage failed: its tool crashed, or its output could not be used. A failed stage stops the run. */
public class StageException extends Exception {
    private static final long serialVersionUID = 1L;

    public StageException(String message) {
        super(message);
    }

    public StageException(String message, Throwable cause) {
        super(message, cause);
    }
}
