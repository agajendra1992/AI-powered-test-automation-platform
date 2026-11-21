package io.qaplatform.api.responses;

import io.qaplatform.api.models.User;

/** Typed view of a user endpoint response; the raw response is kept for header/timing checks. */
public final class UserResponse {

    private final ApiResponse raw;

    public UserResponse(ApiResponse raw) {
        this.raw = raw;
    }

    public int getStatusCode() {
        return raw.status();
    }

    /** Parsed body; only meaningful for 2xx responses. */
    public User getUser() {
        if (!raw.isSuccess()) {
            throw new IllegalStateException("No user body: " + raw.request() + " returned " + raw.status() + ": " + raw.body());
        }
        return raw.as(User.class);
    }

    public ApiResponse raw() {
        return raw;
    }
}
