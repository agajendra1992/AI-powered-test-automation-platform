package io.qaplatform.api.auth;

import java.util.Map;
import java.util.function.Supplier;

/** {@code Authorization: Bearer <token>}; the supplier allows lazy or refreshed tokens. */
public final class BearerTokenAuth implements AuthProvider {

    private final Supplier<String> token;

    public BearerTokenAuth(Supplier<String> token) {
        this.token = token;
    }

    public static BearerTokenAuth of(String token) {
        return new BearerTokenAuth(() -> token);
    }

    @Override
    public Map<String, String> headers() {
        return Map.of("Authorization", "Bearer " + token.get());
    }
}
