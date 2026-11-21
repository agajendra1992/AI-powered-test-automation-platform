package io.qaplatform.core.listeners;

import io.qaplatform.core.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;

/**
 * Re-runs a failed scenario up to {@code retry.count} times (0 by default; CI may set 1).
 * The failed attempt is kept: TestNG reports it as skipped, the failure record is written,
 * and a scenario that passes on retry is counted as flaky in the execution summary.
 * Scenarios tagged {@code @quarantine} or {@code @noretry} are never retried.
 */
public class RetryAnalyzer implements IRetryAnalyzer {

    private static final Logger LOG = LoggerFactory.getLogger(RetryAnalyzer.class);
    private int attempt;

    @Override
    public boolean retry(ITestResult result) {
        int max = ConfigManager.get().retryCount();
        if (ScenarioIds.tags(result).stream().anyMatch(t -> t.equals("@noretry") || t.equals("@quarantine"))) {
            return false;
        }
        if (attempt < max) {
            attempt++;
            result.setAttribute("retryAttempt", attempt);
            ScenarioIds.markRetrying(ScenarioIds.id(result));
            LOG.warn("Retrying '{}' (attempt {}/{})", ScenarioIds.name(result), attempt, max);
            return true;
        }
        return false;
    }
}
