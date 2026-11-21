package io.qaplatform.bdd.hooks;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import io.qameta.allure.Allure;
import io.qaplatform.bdd.context.ScenarioContext;
import io.qaplatform.core.config.ConfigManager;
import io.qaplatform.core.listeners.ScenarioIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Scenario lifecycle. Infrastructure only: no business logic lives in hooks.
 * Browser and API resources are created lazily by {@link ScenarioContext}, so API-only
 * scenarios never launch a browser; tag-specific hooks only add tag-specific evidence.
 */
public class ScenarioHooks {

    private static final Logger LOG = LoggerFactory.getLogger(ScenarioHooks.class);
    private final ScenarioContext context;

    public ScenarioHooks(ScenarioContext context) {
        this.context = context;
    }

    @Before(order = 0)
    public void startScenario(Scenario scenario) {
        context.start(scenario, ScenarioIds.isRetry(ScenarioIds.id(scenario.getUri(), scenario.getLine())));
        MDC.put("scenario", scenario.getName());
        // Allure identifies a test by name + parameters: without the browser, the same scenario run in several
        // browsers (cross-browser workflow, merged reports) collapses into one test with the others as "retries".
        Allure.parameter("browser", ConfigManager.get().browser());
        LOG.info("START {} {}", scenario.getName(), scenario.getSourceTagNames());
    }

    /** UI scenarios: record which browser ran, visible in the report next to the evidence. */
    @Before(value = "@ui", order = 10)
    public void describeBrowser(Scenario scenario) {
        scenario.log("Browser: " + ConfigManager.get().browser());
    }

    /** Runs last (Cucumber executes @After hooks in descending order): cleanup data, attach evidence, release resources. */
    @After(order = 0)
    public void finishScenario(Scenario scenario) {
        try {
            context.finish(scenario.isFailed());
        } finally {
            LOG.info("END   {} -> {}", scenario.getName(), scenario.getStatus());
            MDC.remove("scenario");
        }
    }
}
