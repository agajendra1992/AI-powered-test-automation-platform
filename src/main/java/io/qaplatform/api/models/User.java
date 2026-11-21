package io.qaplatform.api.models;

import java.time.OffsetDateTime;

/** User resource as returned by the API. */
public record User(String id, String name, String email, String job, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
