package io.qaplatform.ai.llm;

import java.util.List;

/**
 * Provider-neutral request.
 *
 * @param purpose   what the call is for (failure-analysis, feature-generation, ...); used for usage tracking
 * @param expectJson ask the provider for a JSON object response where it supports it
 */
public record LlmRequest(String purpose, String system, List<LlmMessage> messages, int maxTokens, boolean expectJson) {

    public static LlmRequest of(String purpose, String system, String user, int maxTokens, boolean expectJson) {
        return new LlmRequest(purpose, system, List.of(LlmMessage.user(user)), maxTokens, expectJson);
    }

    public LlmRequest withMessages(List<LlmMessage> newMessages) {
        return new LlmRequest(purpose, system, List.copyOf(newMessages), maxTokens, expectJson);
    }
}
