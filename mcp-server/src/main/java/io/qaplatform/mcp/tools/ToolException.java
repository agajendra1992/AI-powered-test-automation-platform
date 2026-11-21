package io.qaplatform.mcp.tools;

/**
 * A failure that is safe to show to the MCP client. {@code code} is machine-readable
 * (invalid_argument, forbidden, not_found, conflict, unavailable); the message never contains
 * stack traces, file system internals beyond the project, or secrets.
 */
public class ToolException extends RuntimeException {

    private final String code;

    public ToolException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ToolException invalid(String message) {
        return new ToolException("invalid_argument", message);
    }

    public static ToolException forbidden(String message) {
        return new ToolException("forbidden", message);
    }

    public static ToolException notFound(String message) {
        return new ToolException("not_found", message);
    }

    public static ToolException conflict(String message) {
        return new ToolException("conflict", message);
    }
}
