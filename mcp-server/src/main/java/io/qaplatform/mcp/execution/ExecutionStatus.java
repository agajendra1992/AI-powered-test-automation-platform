package io.qaplatform.mcp.execution;

/**
 * Lifecycle of a run. The outcome is decided only by the framework: Maven's exit code plus the
 * framework's execution-summary.json. Nothing in the MCP layer (or an AI client) can turn a
 * failed run into a passed one.
 */
public enum ExecutionStatus {
    AWAITING_APPROVAL(false),
    QUEUED(false),
    RUNNING(false),
    PASSED(true),
    FAILED(true),
    ERROR(true),
    NO_TESTS(true),
    TIMED_OUT(true),
    CANCELLED(true),
    REJECTED(true);

    private final boolean terminal;

    ExecutionStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean terminal() {
        return terminal;
    }
}
