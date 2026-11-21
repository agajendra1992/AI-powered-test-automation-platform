package io.qaplatform.ai.llm;

/**
 * Provider-neutral response.
 *
 * @param truncated true when the provider stopped because of the output token limit
 */
public record LlmResponse(String provider, String model, String text, long inputTokens, long outputTokens,
                          String stopReason, boolean truncated, long latencyMs) {
}
