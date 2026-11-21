package io.qaplatform.bdd.steps;

import io.cucumber.java.en.Then;
import io.qaplatform.api.assertions.ApiAssertions;
import io.qaplatform.bdd.context.ScenarioContext;
import io.qaplatform.core.config.ConfigManager;

/** Generic response checks reused by every API feature (single definition, no duplicates). */
public class ApiResponseSteps {

    private final ScenarioContext context;

    public ApiResponseSteps(ScenarioContext context) {
        this.context = context;
    }

    @Then("the response status code should be {int}")
    public void status(int expected) {
        ApiAssertions.verify(context.lastResponse()).status(expected);
    }

    @Then("the response should match the {string} schema")
    public void schema(String schemaFile) {
        ApiAssertions.verify(context.lastResponse()).matchesSchema(schemaFile);
    }

    @Then("the response should echo the correlation id")
    public void correlationId() {
        ApiAssertions.verify(context.lastResponse()).echoesCorrelationId();
    }

    @Then("the response time should be within the agreed limit")
    public void responseTime() {
        ApiAssertions.verify(context.lastResponse()).within(ConfigManager.get().getInt("api.max.response.ms", 2000));
    }
}
