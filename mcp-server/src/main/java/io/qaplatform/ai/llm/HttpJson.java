package io.qaplatform.ai.llm;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/** Minimal JSON-over-HTTPS client for the REST providers (java.net.http, no extra dependencies). */
final class HttpJson {

    static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http;
    private final Duration timeout;
    private final String provider;

    HttpJson(String provider, Duration timeout) {
        this.provider = provider;
        this.timeout = timeout;
        // HTTP/1.1 for plain-http local servers (no h2c upgrade); HTTPS still negotiates normally.
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(15)).build();
    }

    JsonNode post(String url, Map<String, String> headers, Object body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        headers.forEach(request::header);
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new LlmException(LlmException.Kind.TIMEOUT, provider + " API timed out after " + timeout.toSeconds() + " s", e);
        } catch (IOException e) {
            throw new LlmException(LlmException.Kind.PROVIDER_ERROR, provider + " API unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Kind.TIMEOUT, provider + " call interrupted", e);
        }
        if (response.statusCode() / 100 != 2) {
            // Error bodies can echo the request; keep only a short, redacted excerpt.
            String excerpt = io.qaplatform.mcp.security.Redactor.redact(response.body());
            excerpt = excerpt.length() > 300 ? excerpt.substring(0, 300) + "..." : excerpt;
            throw new LlmException(LlmException.Kind.PROVIDER_ERROR, provider + " API error " + response.statusCode() + ": " + excerpt);
        }
        return JSON.readTree(response.body());
    }
}
