package io.qaplatform.mcp.execution;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable snapshot of one run, persisted as {@code <state>/executions/<id>/execution.json}.
 *
 * @param summary counts copied from the framework's execution-summary.json (null until finished)
 */
public record ExecutionRecord(
        String id,
        ExecutionRequest request,
        ExecutionStatus status,
        String requestedBy,
        String approvedBy,
        String decisionNote,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Integer exitCode,
        String message,
        Map<String, Object> summary) {

    ExecutionRecord with(ExecutionStatus newStatus, String newMessage) {
        return new ExecutionRecord(id, request, newStatus, requestedBy, approvedBy, decisionNote, createdAt, startedAt,
                finishedAt, exitCode, newMessage, summary);
    }

    ExecutionRecord decided(ExecutionStatus newStatus, String decider, String note) {
        return new ExecutionRecord(id, request, newStatus, requestedBy, decider, note, createdAt, startedAt,
                finishedAt, exitCode, message, summary);
    }

    ExecutionRecord started(Instant at) {
        return new ExecutionRecord(id, request, ExecutionStatus.RUNNING, requestedBy, approvedBy, decisionNote, createdAt, at,
                null, null, "Running", summary);
    }

    ExecutionRecord finished(ExecutionStatus newStatus, Integer code, String newMessage, Map<String, Object> newSummary, Instant at) {
        return new ExecutionRecord(id, request, newStatus, requestedBy, approvedBy, decisionNote, createdAt, startedAt,
                at, code, newMessage, newSummary);
    }
}
