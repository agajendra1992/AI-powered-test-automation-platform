package io.qaplatform.ai;

import io.qaplatform.ai.analysis.FailureCategory;
import io.qaplatform.ai.analysis.FailureEvidence;
import io.qaplatform.ai.analysis.RuleClassifier;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic classification of realistic failure signatures (formats as the framework records them). */
public class RuleClassifierTest {

    private static FailureEvidence evidence(String type, String message, String diagnostics, String api, boolean flaky) {
        return new FailureEvidence("x", "s", "local", "chromium", type, message, "", "Then something", 0, flaky, diagnostics, api, List.of(), List.of());
    }

    @DataProvider
    public Object[][] signatures() {
        return new Object[][] {
            {evidence("io.qaplatform.core.exceptions.FrameworkException", "Application health check failed: HTTP 503", null, null, false),
                FailureCategory.ENVIRONMENT_FAILURE},
            {evidence("com.microsoft.playwright.PlaywrightException", "net::ERR_NAME_NOT_RESOLVED at https://qa.example.com/login", null, null, false),
                FailureCategory.NETWORK_FAILURE},
            {evidence("io.qaplatform.core.exceptions.FrameworkException", "Login failed for 'qa.admin': HTTP 401", null, null, false),
                FailureCategory.AUTHENTICATION_FAILURE},
            {evidence("org.opentest4j.AssertionFailedError", "Page URL expected to match regex\nExpected: .*/dashboard$\nReceived: http://localhost:8000/login",
                "Console errors (1)\n  - Failed to load resource: 401", null, false), FailureCategory.AUTHENTICATION_FAILURE},
            {evidence("com.microsoft.playwright.PlaywrightException",
                "Error: strict mode violation: getByRole(BUTTON, name=\"Save\") resolved to 2 elements", null, null, false), FailureCategory.LOCATOR_FAILURE},
            {evidence("com.microsoft.playwright.TimeoutError", "Timeout 10000ms exceeded.\nwaiting for getByLabel(\"Amount\")", null, null, false),
                FailureCategory.LOCATOR_FAILURE},
            {evidence("com.microsoft.playwright.TimeoutError", "Timeout 30000ms exceeded while waiting for navigation", null, null, false),
                FailureCategory.TIMING_SYNCHRONIZATION},
            {evidence("org.opentest4j.AssertionFailedError", "Expected status 201 but was 500", null,
                "POST /api/users -> 500 (12 ms, correlation abc)", false), FailureCategory.API_FAILURE},
            {evidence("io.qaplatform.core.exceptions.FrameworkException", "Test data not found: testdata/qa/cards.json", null, null, false),
                FailureCategory.TEST_DATA_ISSUE},
            {evidence("java.lang.NullPointerException", "Cannot invoke \"String.length()\" because \"name\" is null", null, null, false),
                FailureCategory.AUTOMATION_DEFECT},
            {evidence("com.microsoft.playwright.PlaywrightException", "Executable doesn't exist at /ms-playwright/chromium-1254", null, null, false),
                FailureCategory.INFRASTRUCTURE_FAILURE},
            {evidence("org.opentest4j.AssertionFailedError", "Locator expected to have text \"Approved\"", "Page errors (1)\n  - TypeError: x is undefined",
                null, false), FailureCategory.APPLICATION_DEFECT},
            {evidence("java.lang.RuntimeException", "something unusual", null, null, false), FailureCategory.UNKNOWN},
        };
    }

    @Test(dataProvider = "signatures")
    public void classifies(FailureEvidence evidence, FailureCategory expected) {
        RuleClassifier.Classification c = new RuleClassifier().classify(evidence);
        assertThat(c.category()).isEqualTo(expected);
        if (expected != FailureCategory.UNKNOWN) {
            assertThat(c.evidence()).as("explains itself").isNotEmpty();
        }
    }

    @Test
    public void knownFlakyLowersConfidenceForWeakSignals() {
        String msg = "Locator expected to have text";
        double stable = new RuleClassifier().classify(evidence("org.opentest4j.AssertionFailedError", msg, null, null, false)).confidence();
        double flaky = new RuleClassifier().classify(evidence("org.opentest4j.AssertionFailedError", msg, null, null, true)).confidence();
        assertThat(flaky).isLessThan(stable);
    }

    @Test
    public void categoryParsingAcceptsLooseModelOutput() {
        assertThat(FailureCategory.parse("timing or synchronization")).contains(FailureCategory.TIMING_SYNCHRONIZATION);
        assertThat(FailureCategory.parse("Locator failure")).contains(FailureCategory.LOCATOR_FAILURE);
        assertThat(FailureCategory.parse("cosmic rays")).isEmpty();
    }
}
