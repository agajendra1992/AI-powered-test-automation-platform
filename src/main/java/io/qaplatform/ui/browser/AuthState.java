package io.qaplatform.ui.browser;

import io.qaplatform.core.config.ConfigManager;
import io.qaplatform.core.reporting.Json;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Builds a Playwright storage state so a browser context starts already authenticated.
 * Used by hybrid tests: log in once over the API, inject the token, skip the login UI.
 */
public final class AuthState {

    private AuthState() {
    }

    /** Storage state that places the token in the application's localStorage. */
    public static String withLocalStorageToken(String token) {
        String key = ConfigManager.get().get("ui.auth.storage.key", "authToken");
        URI base = URI.create(ConfigManager.get().baseUrl());
        String origin = base.getScheme() + "://" + base.getAuthority();
        Map<String, Object> state = Map.of(
                "cookies", List.of(),
                "origins", List.of(Map.of(
                        "origin", origin,
                        "localStorage", List.of(Map.of("name", key, "value", token)))));
        return Json.write(state);
    }
}
