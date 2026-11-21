package io.qaplatform.api.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.RequestOptions;
import io.qaplatform.api.client.ApiContextFactory;
import io.qaplatform.core.reporting.Json;

import java.time.Instant;
import java.util.Map;

/**
 * OAuth2 client-credentials grant with token caching and early refresh.
 * Token requests run on the calling worker's Playwright driver.
 */
public final class OAuth2ClientCredentialsAuth implements AuthProvider {

    private static final long REFRESH_MARGIN_SECONDS = 30;

    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final String scope;
    private String accessToken;
    private Instant expiresAt = Instant.EPOCH;

    public OAuth2ClientCredentialsAuth(String tokenUrl, String clientId, String clientSecret, String scope) {
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scope = scope;
    }

    @Override
    public synchronized Map<String, String> headers() {
        if (accessToken == null || Instant.now().isAfter(expiresAt.minusSeconds(REFRESH_MARGIN_SECONDS))) {
            refresh();
        }
        return Map.of("Authorization", "Bearer " + accessToken);
    }

    private void refresh() {
        APIRequestContext ctx = ApiContextFactory.newContext(tokenUrl, Map.of("Accept", "application/json"));
        FormData form = FormData.create()
                .set("grant_type", "client_credentials")
                .set("client_id", clientId)
                .set("client_secret", clientSecret);
        if (scope != null && !scope.isBlank()) {
            form.set("scope", scope);
        }
        APIResponse response = ctx.post(tokenUrl, RequestOptions.create().setForm(form));
        try {
            if (response.status() != 200) {
                throw new IllegalStateException("OAuth2 token request failed: HTTP " + response.status());
            }
            JsonNode json = Json.read(response.text(), JsonNode.class);
            accessToken = json.path("access_token").asText();
            expiresAt = Instant.now().plusSeconds(json.path("expires_in").asLong(300));
        } finally {
            response.dispose();
            ctx.dispose();
        }
    }
}
