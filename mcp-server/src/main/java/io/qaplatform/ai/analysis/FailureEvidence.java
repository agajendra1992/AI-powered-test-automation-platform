package io.qaplatform.ai.analysis;

import java.util.List;
import java.util.Map;

/**
 * Everything the framework recorded about one failed scenario. Collected deterministically from
 * the run's outputs; this is what both the rules and the optional model look at.
 */
public record FailureEvidence(
        String executionId,
        String scenario,
        String environment,
        String browser,
        String errorType,
        String message,
        String stackTop,
        String failedStep,
        int retryAttempt,
        boolean flaky,
        String browserDiagnostics,
        String apiCalls,
        List<String> logTail,
        List<Map<String, Object>> history) {

    /** All evidence as one text, used to check that cited evidence really exists. */
    public String corpus() {
        return String.join("\n", nz(errorType), nz(message), nz(stackTop), nz(failedStep), nz(browserDiagnostics), nz(apiCalls),
                String.join("\n", logTail));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
