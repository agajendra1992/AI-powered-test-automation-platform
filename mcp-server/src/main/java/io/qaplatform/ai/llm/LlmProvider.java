package io.qaplatform.ai.llm;

/**
 * A chat-completion backend. Implementations: Anthropic (official SDK), OpenAI and any
 * OpenAI-compatible local server (REST), Google Gemini (REST), and a disabled provider.
 * Nothing in the test framework depends on this interface: AI is optional (AC29).
 */
public interface LlmProvider {

    /** anthropic, openai, gemini, local or none. */
    String name();

    String model();

    boolean enabled();

    LlmResponse complete(LlmRequest request);

    /** Why the provider is disabled (configuration hint), or null when enabled. */
    default String disabledReason() {
        return null;
    }
}
