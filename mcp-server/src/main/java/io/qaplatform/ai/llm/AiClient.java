package io.qaplatform.ai.llm;

import io.qaplatform.mcp.security.Redactor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the AI features use: renders a prompt, enforces the token budget, redacts everything
 * sent to the provider, records usage, and parses JSON answers (one repair attempt). AI output
 * is advice; callers validate it and never let it change a test result.
 */
public final class AiClient {

    public record Answer(String text, JsonNode json, LlmResponse response, String promptVersion) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final LlmProvider provider;
    private final PromptManager prompts;
    private final TokenUsageTracker usage;
    private final int maxOutputTokens;
    private final int contextTokens;

    public AiClient(LlmProvider provider, PromptManager prompts, TokenUsageTracker usage, int maxOutputTokens) {
        this(provider, prompts, usage, maxOutputTokens, 0);
    }

    public AiClient(LlmProvider provider, PromptManager prompts, TokenUsageTracker usage, int maxOutputTokens, int contextTokens) {
        this.provider = provider;
        this.prompts = prompts;
        this.usage = usage;
        this.maxOutputTokens = maxOutputTokens;
        this.contextTokens = contextTokens;
    }

    /** Rough token estimate (about 3.5 characters per token for English and code). */
    static int estimateTokens(LlmRequest request) {
        long chars = request.system().length() + request.messages().stream().mapToLong(m -> m.content().length()).sum();
        return (int) Math.ceil(chars / 3.5);
    }

    public boolean enabled() {
        return provider.enabled();
    }

    public LlmProvider provider() {
        return provider;
    }

    public TokenUsageTracker usage() {
        return usage;
    }

    public Answer text(String promptName, Map<String, String> values) {
        PromptManager.Rendered prompt = prompts.render(promptName, values);
        LlmResponse response = call(LlmRequest.of(promptName, prompt.system(), prompt.user(), maxOutputTokens, false));
        return new Answer(stripFences(response.text()), null, response, prompt.version());
    }

    public Answer json(String promptName, Map<String, String> values) {
        PromptManager.Rendered prompt = prompts.render(promptName, values);
        LlmRequest request = LlmRequest.of(promptName, prompt.system(), prompt.user(), maxOutputTokens, true);
        LlmResponse response = call(request);
        JsonNode parsed = parseObject(response.text());
        if (parsed == null) {
            List<LlmMessage> retry = new ArrayList<>(request.messages());
            retry.add(LlmMessage.assistant(response.text()));
            retry.add(LlmMessage.user("That was not a single valid JSON object. Reply again with only the JSON object, no prose, no code fences."));
            response = call(request.withMessages(retry));
            parsed = parseObject(response.text());
            if (parsed == null) {
                throw new LlmException(LlmException.Kind.BAD_OUTPUT, "The model did not return valid JSON after one retry");
            }
        }
        return new Answer(response.text(), parsed, response, prompt.version());
    }

    private LlmResponse call(LlmRequest request) {
        if (!provider.enabled()) {
            return provider.complete(request);
        }
        usage.checkBudget();
        if (contextTokens > 0) {
            int needed = estimateTokens(request) + request.maxTokens();
            if (needed > contextTokens) {
                throw new LlmException(LlmException.Kind.PROMPT_TOO_LARGE, "Prompt (~" + estimateTokens(request) + " tokens) plus answer budget ("
                        + request.maxTokens() + ") exceeds the model's context window (" + contextTokens + " tokens, QA_AI_CONTEXT_TOKENS). "
                        + "Use a model with a larger context (for Ollama: start it with OLLAMA_CONTEXT_LENGTH=16384) or lower QA_AI_MAX_OUTPUT_TOKENS.");
            }
        }
        List<LlmMessage> redacted = request.messages().stream().map(m -> new LlmMessage(m.role(), Redactor.redact(m.content()))).toList();
        LlmRequest safe = new LlmRequest(request.purpose(), Redactor.redact(request.system()), redacted, request.maxTokens(), request.expectJson());
        long start = System.nanoTime();
        try {
            LlmResponse response = provider.complete(safe);
            usage.record(new TokenUsageTracker.Usage(Instant.now(), provider.name(), response.model(), request.purpose(),
                    response.inputTokens(), response.outputTokens(), response.latencyMs(), response.truncated() ? "truncated" : "ok"));
            return response;
        } catch (LlmException e) {
            usage.record(new TokenUsageTracker.Usage(Instant.now(), provider.name(), provider.model(), request.purpose(), 0, 0,
                    (System.nanoTime() - start) / 1_000_000, e.kind().name().toLowerCase(java.util.Locale.ROOT)));
            throw e;
        }
    }

    /** Extracts the first JSON object from a model reply (tolerates code fences and surrounding prose). */
    public static JsonNode parseObject(String text) {
        if (text == null) {
            return null;
        }
        String s = stripFences(text);
        int start = s.indexOf('{');
        while (start >= 0) {
            int depth = 0;
            boolean inString = false;
            for (int i = start; i < s.length(); i++) {
                char c = s.charAt(i);
                if (inString) {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"') {
                        inString = false;
                    }
                } else if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}' && --depth == 0) {
                    try {
                        JsonNode node = JSON.readTree(s.substring(start, i + 1));
                        return node.isObject() ? node : null;
                    } catch (JacksonException e) {
                        break;
                    }
                }
            }
            start = s.indexOf('{', start + 1);
        }
        return null;
    }

    public static String stripFences(String text) {
        String s = text.strip();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return s.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return s;
    }
}
