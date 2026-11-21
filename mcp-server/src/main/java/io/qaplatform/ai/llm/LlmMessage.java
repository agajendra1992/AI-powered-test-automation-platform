package io.qaplatform.ai.llm;

/** One conversation turn. {@code role} is "user" or "assistant". */
public record LlmMessage(String role, String content) {

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage("assistant", content);
    }
}
