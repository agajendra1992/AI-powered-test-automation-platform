package io.qaplatform.api.services;

import io.qameta.allure.Allure;
import io.qaplatform.api.client.ApiClient;
import io.qaplatform.api.models.LoginRequest;
import io.qaplatform.api.models.TokenResponse;
import io.qaplatform.api.requests.ApiRequest;
import io.qaplatform.api.responses.ApiResponse;
import io.qaplatform.core.exceptions.FrameworkException;

/** Authentication endpoints of the application under test. */
public class AuthService {

    private final ApiClient client;

    public AuthService(ApiClient client) {
        this.client = client;
    }

    /** Not an {@code @Step}: step annotations record arguments, and this one is a password. */
    public ApiResponse login(String username, String password) {
        return client.send(ApiRequest.post("/api/auth/login").body(new LoginRequest(username, password)).build());
    }

    /** Logs in and returns the bearer token; any non-200 is a setup failure, not a test failure. */
    public String token(String username, String password) {
        ApiResponse r = Allure.step("Obtain API token for '" + username + "'", () -> login(username, password));
        if (r.status() != 200) {
            throw new FrameworkException("Login failed for '" + username + "': HTTP " + r.status());
        }
        return r.as(TokenResponse.class).token();
    }
}
