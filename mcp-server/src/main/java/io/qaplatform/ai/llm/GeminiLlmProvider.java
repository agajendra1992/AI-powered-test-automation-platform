package io.qaplatform.ai.llm;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Google Gemini through the generateContent REST API (key sent in the x-goog-api-key header, never in the URL). */
public final class GeminiLlmProvider implements LlmProvider {

    private final String model;
    private final String baseUrl;
    private final String apiKey;
    private final HttpJson http;

    public GeminiLlmProvider(AiConfig config, String apiKey) {
        this.model = config.model();
        String url = config.baseUrl() == null ? "https://generativelanguage.googleapis.com/v1beta" : config.baseUrl();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.apiKey = apiKey;
        this.http = new HttpJson("gemini", config.timeout());
    }

    @Override
    public String name() {
        return "gemini";
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
        List<Map<String, Object>> contents = new ArrayList<>();
        for (LlmMessage m : request.messages()) {
            contents.add(Map.of("role", "assistant".equals(m.role()) ? "model" : "user", "parts", List.of(Map.of("text", m.content()))));
        }
        Map<String, Object> generation = new LinkedHashMap<>();
        generation.put("maxOutputTokens", request.maxTokens());
        if (request.expectJson()) {
            generation.put("responseMimeType", "application/json");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", request.system()))));
        body.put("contents", contents);
        body.put("generationConfig", generation);
        long start = System.nanoTime();
        JsonNode response = http.post(baseUrl + "/models/" + model + ":generateContent", Map.of("x-goog-api-key", apiKey), body);
        JsonNode candidate = response.path("candidates").path(0);
        String finish = candidate.path("finishReason").asString("unknown");
        if ("SAFETY".equals(finish) || "PROHIBITED_CONTENT".equals(finish) || response.path("promptFeedback").has("blockReason")) {
            throw new LlmException(LlmException.Kind.REFUSED, "Gemini blocked the request or response (" + finish + ")");
        }
        StringBuilder text = new StringBuilder();
        candidate.path("content").path("parts").forEach(p -> text.append(p.path("text").asString("")));
        JsonNode usage = response.path("usageMetadata");
        return new LlmResponse("gemini", model, text.toString(), usage.path("promptTokenCount").asLong(0),
                usage.path("candidatesTokenCount").asLong(0), finish, "MAX_TOKENS".equals(finish), (System.nanoTime() - start) / 1_000_000);
    }
}
