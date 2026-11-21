package io.qaplatform.ai.llm;

/** Builds the configured provider. Missing settings disable AI with a clear reason instead of failing the server. */
public final class LlmFactory {

    private LlmFactory() {
    }

    public static LlmProvider create(AiConfig config) {
        return create(config, System.getenv(config.apiKeyEnv() == null || config.apiKeyEnv().isEmpty() ? "QA_AI_UNSET" : config.apiKeyEnv()));
    }

    /** {@code apiKey} is passed in (not read here) so tests never touch real credentials. */
    public static LlmProvider create(AiConfig config, String apiKey) {
        String provider = config.provider();
        if ("none".equals(provider)) {
            return new DisabledLlmProvider("AI is disabled (QA_AI_PROVIDER=none). Deterministic results are still returned.");
        }
        if (!"anthropic".equals(provider) && (config.model() == null || config.model().isBlank())) {
            return new DisabledLlmProvider("QA_AI_MODEL must be set for provider '" + provider + "'");
        }
        boolean keyRequired = !"local".equals(provider);
        if (keyRequired && (apiKey == null || apiKey.isBlank())) {
            return new DisabledLlmProvider("Environment variable " + config.apiKeyEnv() + " is not set for provider '" + provider + "'");
        }
        return switch (provider) {
            case "anthropic" -> new AnthropicLlmProvider(config, apiKey);
            case "openai", "local" -> new OpenAiCompatibleLlmProvider(provider, config, apiKey);
            case "gemini" -> new GeminiLlmProvider(config, apiKey);
            default -> new DisabledLlmProvider("Unknown provider " + provider);
        };
    }
}
