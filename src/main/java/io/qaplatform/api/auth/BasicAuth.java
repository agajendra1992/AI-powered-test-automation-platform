package io.qaplatform.api.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/** HTTP Basic authentication. */
public final class BasicAuth implements AuthProvider {

    private final String header;

    public BasicAuth(String username, String password) {
        this.header = "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Map<String, String> headers() {
        return Map.of("Authorization", header);
    }
}
