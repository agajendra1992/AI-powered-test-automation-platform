package io.qaplatform.ai.planning;

import java.time.Instant;
import java.util.List;

/**
 * A validated, not-yet-executed selection. Exactly one of: full regression, a tag expression, or
 * a list of scenario names. Plans are stored and executed later by id with execute_test_plan, so
 * a human can read what will run before anything starts.
 */
public record TestPlan(
        String planId,
        String request,
        String source,
        boolean fullRegression,
        String tagExpression,
        List<String> scenarioNames,
        String environment,
        String browser,
        int testCases,
        int regressionTestCases,
        boolean approvalRequired,
        String explanation,
        String createdBy,
        Instant createdAt,
        String executionId) {

    public TestPlan executed(String id) {
        return new TestPlan(planId, request, source, fullRegression, tagExpression, scenarioNames, environment, browser, testCases,
                regressionTestCases, approvalRequired, explanation, createdBy, createdAt, id);
    }
}
