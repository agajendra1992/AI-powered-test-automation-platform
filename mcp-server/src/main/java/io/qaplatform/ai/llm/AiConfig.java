package io.qaplatform.ai.llm;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * AI settings, from {@code -Dqa.ai.<key>} or {@code QA_AI_<KEY>}. API keys are read from the
 * environment variable named by {@code api.key.env} (default per provider) and are never logged,
 * stored or returned by any tool.
 *
 * @param provider none (default), anthropic, openai, gemini or local
 * @param model    required for openai, gemini and local; anthropic defaults to claude-opus-5-5
 * @param baseUrl  override the API endpoint (local servers, proxies, tests)
 * @param contextTokens the model's context window if small and known (e.g. 4096 for a default Ollama model);
 *                      prompts that would not fit are refused instead of being silently truncated. 0 = no check.
 */
public record AiConfig(String provider, String model, String baseUrl, String apiKeyEnv, Duration timeout,
                       int maxOutputTokens, long dailyTokenBudget, int contextTokens) {

    public static final Set<String> PROVIDERS = Set.of("none", "anthropic", "openai", "gemini", "local");
    public static final String DEFAULT_ANTHROPIC_MODEL = "claude-opus-5-5";

    public static AiConfig fromEnvironment() {
        return from(key -> {
            String sys = System.getProperty(key);
            if (sys != null && !sys.isBlank()) {
                return sys.trim();
            }
            String env = System.getenv(key.toUpperCase(Locale.ROOT).replace('.', '_'));
            return env == null || env.isBlank() ? null : env.trim();
        });
    }

    public static AiConfig from(Function<String, String> lookup) {
        Function<String, String> get = k -> lookup.apply("qa.ai." + k);
        String provider = orDefault(get.apply("provider"), "none").toLowerCase(Locale.ROOT);
        if (!PROVIDERS.contains(provider)) {
            throw new IllegalStateException("qa.ai.provider must be one of " + PROVIDERS + ", got " + provider);
        }
        String defaultKeyEnv = switch (provider) {
            case "anthropic" -> "ANTHROPIC_API_KEY";
            case "openai" -> "OPENAI_API_KEY";
            case "gemini" -> "GEMINI_API_KEY";
            case "local" -> "LOCAL_LLM_API_KEY";
            default -> "";
        };
        String model = get.apply("model");
        if (model == null && "anthropic".equals(provider)) {
            model = DEFAULT_ANTHROPIC_MODEL;
        }
        int defaultMax = "anthropic".equals(provider) ? 16_000 : 4_000;
        return new AiConfig(provider, model, get.apply("base.url"), orDefault(get.apply("api.key.env"), defaultKeyEnv),
                Duration.ofSeconds(Long.parseLong(orDefault(get.apply("timeout.seconds"), "180"))),
                Integer.parseInt(orDefault(get.apply("max.output.tokens"), String.valueOf(defaultMax))),
                Long.parseLong(orDefault(get.apply("daily.token.budget"), "1000000")),
                Integer.parseInt(orDefault(get.apply("context.tokens"), "0")));
    }

    private static String orDefault(String v, String d) {
        return v == null || v.isBlank() ? d : v;
    }
}
