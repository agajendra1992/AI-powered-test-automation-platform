package io.qaplatform.contract;

import au.com.dius.pact.provider.ConsumerInfo;
import au.com.dius.pact.provider.ProviderInfo;
import au.com.dius.pact.provider.ProviderVerifier;
import au.com.dius.pact.provider.VerificationResult;
import io.qaplatform.api.client.ApiClient;
import io.qaplatform.api.services.AuthService;
import io.qaplatform.core.config.ConfigManager;
import io.qaplatform.core.reporting.Json;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.testng.annotations.Test;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider side: replays the pact written by {@link QaShopApiConsumerPactTest} against the running
 * QA Shop API ({@code api.base.url}) and fails on any response that breaks it.
 * <p>
 * The provider must run with {@code PACT_PROVIDER_STATES=1} so it accepts state set-up calls at
 * {@code /_pact/provider-states}. Credentials are never in the pact: a request filter swaps the
 * example values for the configured account and a fresh token at replay time.
 */
public class QaShopApiProviderPactTest {

    @Test
    public void providerHonoursTheFrameworkContract() throws Exception {
        ConfigManager config = ConfigManager.get();
        String baseUrl = config.apiBaseUrl();
        URI base = URI.create(baseUrl);
        File pactFile = new File("target/pacts/" + QaShopApiConsumerPactTest.CONSUMER + "-" + QaShopApiConsumerPactTest.PROVIDER + ".json");
        assertThat(pactFile).as("pact file from the consumer test (run testng-contract.xml, consumer first)").exists();

        String username = config.get("auth.username");
        String password = config.get("auth.password");
        String token;
        try (ApiClient client = new ApiClient(baseUrl, null)) {
            token = new AuthService(client).token(username, password);
        }

        ProviderInfo provider = new ProviderInfo(QaShopApiConsumerPactTest.PROVIDER);
        provider.setProtocol(base.getScheme());
        provider.setHost(base.getHost());
        provider.setPort(base.getPort() == -1 ? ("https".equals(base.getScheme()) ? 443 : 80) : base.getPort());
        provider.setStateChangeUrl(new URL(baseUrl + "/_pact/provider-states"));
        provider.setRequestFilter((Consumer<HttpRequest>) request -> {
            if (request.getPath().startsWith("/api/auth/login") && request instanceof ClassicHttpRequest classic) {
                classic.setEntity(new StringEntity(Json.write(Map.of("username", username, "password", password)), ContentType.APPLICATION_JSON));
            } else if (request.containsHeader("Authorization")) {
                request.setHeader("Authorization", "Bearer " + token);
            }
        });
        ConsumerInfo consumer = new ConsumerInfo(QaShopApiConsumerPactTest.CONSUMER);
        consumer.setPactSource(new au.com.dius.pact.core.model.FileSource(pactFile, null));
        provider.getConsumers().add(consumer);

        ProviderVerifier verifier = new ProviderVerifier();
        verifier.setProviderVersion(() -> System.getProperty("provider.version", "local"));
        List<VerificationResult> results = verifier.verifyProvider(provider);

        List<String> failures = results.stream()
                .filter(r -> r instanceof VerificationResult.Failed)
                .map(r -> ((VerificationResult.Failed) r).getDescription() + " " + ((VerificationResult.Failed) r).getFailures())
                .toList();
        assertThat(results).isNotEmpty();
        assertThat(failures).as("contract failures").isEmpty();
    }
}
