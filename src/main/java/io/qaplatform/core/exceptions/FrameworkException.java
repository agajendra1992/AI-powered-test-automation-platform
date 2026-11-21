package io.qaplatform.core.exceptions;

/**
 * Raised for framework or environment problems (bad configuration, missing test data,
 * failed test-data setup). Distinguishes "the harness broke" from an assertion failure,
 * which matters for failure classification and reporting.
 */
public class FrameworkException extends RuntimeException {

    public FrameworkException(String message) {
        super(message);
    }

    public FrameworkException(String message, Throwable cause) {
        super(message, cause);
    }
}
