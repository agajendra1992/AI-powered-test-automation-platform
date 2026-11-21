package io.qaplatform.bdd.steps;

import io.cucumber.java.en.Given;
import io.qaplatform.api.requests.ApiRequest;
import io.qaplatform.api.responses.ApiResponse;
import io.qaplatform.bdd.context.ScenarioContext;
import io.qaplatform.core.exceptions.FrameworkException;

/** Steps shared across domains: availability checks and administrator sign-in. */
public class CommonSteps {

    private final ScenarioContext context;

    public CommonSteps(ScenarioContext context) {
        this.context = context;
    }

    /** Fails fast as an environment problem (not a test failure) when the application is down. */
    @Given("the (payment )application is available")
    @Given("the user/payment API is available")
    public void applicationIsAvailable() {
        ApiResponse health = context.apiClient(null).send(ApiRequest.get("/api/health").build());
        if (health.status() != 200) {
            throw new FrameworkException("Application health check failed: HTTP " + health.status());
        }
    }

    /** Signs in by API token (fast, reliable); the login UI itself is covered by login.feature. */
    @Given("the administrator is signed in")
    @Given("the administrator is signed in through an API token")
    public void administratorSignedIn() {
        context.authenticateBrowserWithToken(context.adminToken());
    }
}
