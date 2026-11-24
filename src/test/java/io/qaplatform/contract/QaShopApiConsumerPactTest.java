package io.qaplatform.contract;

import au.com.dius.pact.consumer.ConsumerPactBuilder;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.RequestResponsePact;
import io.qaplatform.api.auth.BearerTokenAuth;
import io.qaplatform.api.client.ApiClient;
import io.qaplatform.api.models.User;
import io.qaplatform.api.models.UserRequest;
import io.qaplatform.api.services.AuthService;
import io.qaplatform.api.services.UserService;
import io.qaplatform.api.responses.UserResponse;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.Map;

import static au.com.dius.pact.consumer.ConsumerPactRunnerKt.runConsumerTest;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consumer side of the contract between this framework's API layer ("qa-platform-framework") and the
 * QA Shop API ("qa-shop-api").
 * <p>
 * Each interaction states what the framework sends and the smallest response it relies on, using type
 * matchers rather than exact values. The framework's real {@link AuthService} and {@link UserService}
 * are run against Pact's mock server; when they pass, Pact writes {@code target/pacts/*.json},
 * which {@link QaShopApiProviderPactTest} then replays against the real provider.
 */
public class QaShopApiConsumerPactTest {

    static final String CONSUMER = "qa-platform-framework";
    static final String PROVIDER = "qa-shop-api";
    static final String EXISTING_USER = "c0ffee000001";
    static final String MISSING_USER = "c0ffee000404";
    /** ISO-8601 date-time with offset, as the framework's {@code OffsetDateTime} fields expect. */
    private static final String ISO_INSTANT = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})";
    private static final String BEARER = "Bearer [A-Za-z0-9_\\-]+";

    private static PactDslJsonBody user() {
        return new PactDslJsonBody()
                .stringType("id", EXISTING_USER)
                .stringType("name", "Contract User")
                .stringType("email", "contract.user@example.com")
                .stringType("job", "QA")
                .stringMatcher("createdAt", ISO_INSTANT, "2026-11-24T01:06:03.656023+00:00")
                .stringMatcher("updatedAt", ISO_INSTANT, "2026-11-24T01:06:03.656023+00:00");
    }

    private static RequestResponsePact pact() {
        return ConsumerPactBuilder.consumer(CONSUMER).hasPactWith(PROVIDER)
                .uponReceiving("a sign-in with valid credentials")
                    .path("/api/auth/login").method("POST")
                    .headers(Map.of("Content-Type", "application/json"))
                    .body(new PactDslJsonBody().stringType("username", "qa.admin").stringType("password", "secret-value"))
                .willRespondWith().status(200)
                    .body(new PactDslJsonBody().stringType("token", "token-value").integerType("expires_in", 3600))
                .given("a user with id " + EXISTING_USER + " exists")
                .uponReceiving("a request for an existing user")
                    .path("/api/users/" + EXISTING_USER).method("GET")
                    .matchHeader("Authorization", BEARER, "Bearer contract-token")
                .willRespondWith().status(200).body(user())
                .given("no user with id " + MISSING_USER + " exists")
                .uponReceiving("a request for a user that does not exist")
                    .path("/api/users/" + MISSING_USER).method("GET")
                    .matchHeader("Authorization", BEARER, "Bearer contract-token")
                .willRespondWith().status(404).body(new PactDslJsonBody().stringType("detail", "User not found"))
                .given("no user with email new.user@example.com exists")
                .uponReceiving("a request to create a user")
                    .path("/api/users").method("POST")
                    .matchHeader("Authorization", BEARER, "Bearer contract-token")
                    .headers(Map.of("Content-Type", "application/json"))
                    .body(new PactDslJsonBody()
                            .stringType("name", "New User")
                            .stringMatcher("email", "[^@\\s]+@[^@\\s]+\\.[a-z]+", "new.user@example.com")
                            .stringType("job", "QA"))
                .willRespondWith().status(201).body(user())
                .toPact();
    }

    /** Pact merges into an existing file; start clean so removed or renamed interactions do not linger. */
    @BeforeClass
    public void removeOldPact() throws IOException {
        Files.deleteIfExists(Path.of("target", "pacts", CONSUMER + "-" + PROVIDER + ".json"));
    }

    @Test
    public void frameworkApiLayerHonoursTheContract() {
        MockProviderConfig config = MockProviderConfig.createDefault();
        PactVerificationResult result = runConsumerTest(pact(), config, (server, context) -> {
            try (ApiClient anonymous = new ApiClient(server.getUrl(), null);
                 ApiClient authorised = new ApiClient(server.getUrl(), BearerTokenAuth.of("contract-token"))) {
                assertThat(new AuthService(anonymous).token("qa.admin", "secret-value")).isNotBlank();

                UserService users = new UserService(authorised);
                User existing = users.getUser(EXISTING_USER).getUser();
                assertThat(existing.id()).isEqualTo(EXISTING_USER);
                assertThat(existing.createdAt()).isNotNull();

                assertThat(users.getUser(MISSING_USER).getStatusCode()).isEqualTo(404);

                UserResponse created = users.createUser(new UserRequest("New User", "new.user@example.com", "QA"));
                assertThat(created.getStatusCode()).isEqualTo(201);
                assertThat(created.getUser().email()).contains("@");
            }
            return null;
        });
        assertThat(result).as(result.getDescription()).isInstanceOf(PactVerificationResult.Ok.class);
    }
}
