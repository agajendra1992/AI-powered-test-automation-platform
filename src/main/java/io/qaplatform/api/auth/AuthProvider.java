package io.qaplatform.api.auth;

import java.util.Map;

/** Supplies the auth headers for a request. Implementations must be thread-safe. */
@FunctionalInterface
public interface AuthProvider {
    Map<String, String> headers();
}
