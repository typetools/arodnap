package org.arodnap.engine.analysis;

/** An analysis could not complete: the program did not compile, a Checker Framework run failed, and so on. */
public class AnalysisException extends Exception {
    private static final long serialVersionUID = 1L;

    public AnalysisException(String message) {
        super(message);
    }

    public AnalysisException(String message, Throwable cause) {
        super(message, cause);
    }
}
