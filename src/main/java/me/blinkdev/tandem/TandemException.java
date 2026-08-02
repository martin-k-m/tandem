package me.blinkdev.tandem;

/**
 * Thrown when a workflow cannot proceed.
 *
 * <p>Unchecked on purpose. A workflow step already declares {@code throws
 * Exception}, so forcing callers to also catch a framework exception adds
 * ceremony without adding a decision they can act on.
 */
public class TandemException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TandemException(String message) {
        super(message);
    }

    public TandemException(String message, Throwable cause) {
        super(message, cause);
    }
}
