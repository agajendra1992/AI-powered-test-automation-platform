package io.qaplatform.ai.llm;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions, and any server exposing the same API (Ollama, vLLM, LM Studio,
 * llama.cpp server, gateways): provider "openai" uses https://api.openai.com/v1 and
 * {@code max_completion_tokens}; provider "local" uses the configured base URL (default
 * Ollama, http://localhost:11434/v1) and {@code max_tokens}.
 */
public final class OpenAiCompatibleLlmProvider implements LlmProvider {

    private final String name;
    private final String model;
    private final String baseUrl;
    private final String apiKey;
    private final HttpJson http;

    public OpenAiCompatibleLlmProvider(String name, AiConfig config, String apiKey) {
        this.name = name;
        this.model = config.model();
        String defaultUrl = "openai".equals(name) ? "https://api.openai.com/v1" : "http://localhost:11434/v1";
        this.baseUrl = stripSlash(config.baseUrl() == null ? defaultUrl : config.baseUrl());
        this.apiKey = apiKey;
        this.http = new HttpJson(name, config.timeout());
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", request.system()));
        request.messages().forEach(m -> messages.add(Map.of("role", m.role(), "content", m.content())));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("openai".equals(name) ? "max_completion_tokens" : "max_tokens", request.maxTokens());
        if (request.expectJson()) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        Map<String, String> headers = new HashMap<>();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        long start = System.nanoTime();
        JsonNode response = http.post(baseUrl + "/chat/completions", headers, body);
        JsonNode choice = response.path("choices").path(0);
        String finish = choice.path("finish_reason").asString("unknown");
        if ("content_filter".equals(finish)) {
            throw new LlmException(LlmException.Kind.REFUSED, "The model's content filter stopped the response");
        }
        JsonNode usage = response.path("usage");
        return new LlmResponse(name, response.path("model").asString(model), choice.path("message").path("content").asString(""),
                usage.path("prompt_tokens").asLong(0), usage.path("completion_tokens").asLong(0), finish, "length".equals(finish),
                (System.nanoTime() - start) / 1_000_000);
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
