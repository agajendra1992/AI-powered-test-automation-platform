package io.qaplatform.api.responses;

import com.fasterxml.jackson.databind.JsonNode;
import io.qaplatform.core.reporting.Json;

import java.util.Map;
import java.util.Optional;

/**
 * Fully buffered HTTP response. Buffering lets us dispose the Playwright response immediately,
 * attach the body to Allure, and parse it many times.
 */
public record ApiResponse(
        String request,
        int status,
        Map<String, String> headers,
        String body,
        long durationMs,
        String correlationId) {

    public <T> T as(Class<T> type) {
        return Json.read(body, type);
    }

    public JsonNode json() {
        return Json.read(body, JsonNode.class);
    }

    /** JSON Pointer lookup, e.g. {@code "/data/0/id"}. Missing nodes return empty. */
    public Optional<String> at(String pointer) {
        JsonNode node = json().at(pointer);
        return node.isMissingNode() || node.isNull() ? Optional.empty() : Optional.of(node.asText());
    }

    public Optional<String> header(String name) {
        return headers.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }
}
