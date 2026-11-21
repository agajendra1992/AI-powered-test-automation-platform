package io.qaplatform.api.models;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TokenResponse(String token, @JsonProperty("expires_in") long expiresIn) {
}
