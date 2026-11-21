package io.qaplatform.api.auth;

import java.util.Map;

/** Anonymous requests (public endpoints and negative tests). */
public final class NoAuth implements AuthProvider {
    @Override
    public Map<String, String> headers() {
        return Map.of();
    }
}
