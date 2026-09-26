package org.arodnap.engine.inputs;

/**
 * The project cannot be analyzed as it is: no Java sources were compiled, the build uses Lombok,
 * the build failed, and similar. The message says what to do about it.
 */
public class UnsupportedProjectException extends Exception {
    private static final long serialVersionUID = 1L;

    public UnsupportedProjectException(String message) {
        super(message);
    }

    public UnsupportedProjectException(String message, Throwable cause) {
        super(message, cause);
    }
}
