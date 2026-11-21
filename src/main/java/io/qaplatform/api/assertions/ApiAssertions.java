package io.qaplatform.api.assertions;

import io.qameta.allure.Allure;
import io.qaplatform.api.client.ApiClient;
import io.qaplatform.api.responses.ApiResponse;
import io.qaplatform.api.schemas.SchemaValidator;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fluent, self-describing response assertions:
 * {@code ApiAssertions.verify(resp).status(201).within(800).matchesSchema("user.schema.json")}.
 * Failure messages always include the request line and response body.
 */
public final class ApiAssertions {

    private final ApiResponse response;

    private ApiAssertions(ApiResponse response) {
        this.response = response;
    }

    public static ApiAssertions verify(ApiResponse response) {
        return new ApiAssertions(response);
    }

    public ApiAssertions status(int expected) {
        Allure.step("Status is " + expected, () ->
                assertThat(response.status())
                        .as("%s returned %d: %s", response.request(), response.status(), response.body())
                        .isEqualTo(expected));
        return this;
    }

    public ApiAssertions within(long maxMs) {
        Allure.step("Responded within " + maxMs + " ms (actual " + response.durationMs() + " ms)", () ->
                assertThat(response.durationMs()).as("response time of %s", response.request()).isLessThanOrEqualTo(maxMs));
        return this;
    }

    public ApiAssertions matchesSchema(String schemaName) {
        Allure.step("Body matches schema " + schemaName, () -> {
            Set<String> errors = SchemaValidator.validate(schemaName, response.json());
            assertThat(errors).as("schema violations for %s", response.request()).isEmpty();
        });
        return this;
    }

    public ApiAssertions header(String name, String expected) {
        Allure.step("Header " + name + " = " + expected, () ->
                assertThat(response.header(name)).as("header %s", name).contains(expected));
        return this;
    }

    /** The service must echo the correlation id we sent, proving end-to-end traceability. */
    public ApiAssertions echoesCorrelationId() {
        return header(ApiClient.CORRELATION_HEADER, response.correlationId());
    }

    public ApiAssertions field(String jsonPointer, String expected) {
        Allure.step("Field " + jsonPointer + " = " + expected, () ->
                assertThat(response.at(jsonPointer)).as("field %s in %s", jsonPointer, response.body()).contains(expected));
        return this;
    }

    public ApiAssertions bodyContains(String fragment) {
        Allure.step("Body contains '" + fragment + "'", () -> assertThat(response.body()).contains(fragment));
        return this;
    }
}
