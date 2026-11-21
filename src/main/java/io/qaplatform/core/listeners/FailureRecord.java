package io.qaplatform.core.listeners;

import java.time.Instant;
import java.util.List;

/**
 * Machine-readable description of one failed test, written to {@code target/test-failures/}.
 * This is the contract between the deterministic engine and the (optional) AI layer.
 */
public record FailureRecord(
        String testId,
        String name,
        String kind,          // testng | cucumber
        List<String> groups,
        String environment,
        String browser,
        String errorType,
        String message,
        String stackTrace,
        long durationMs,
        int retryAttempt,
        Instant timestamp) {
}
