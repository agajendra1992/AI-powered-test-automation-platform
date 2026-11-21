package io.qaplatform.ai.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;

import java.util.stream.Collectors;

/**
 * Claude through the official Anthropic Java SDK. Requests are streamed (long generations do
 * not hit HTTP timeouts) and accumulated into the final message. The default model is
 * claude-opus-5-5, which always uses adaptive thinking, so no thinking parameter is sent.
 */
public final class AnthropicLlmProvider implements LlmProvider {

    private final AnthropicClient client;
    private final String model;

    public AnthropicLlmProvider(AiConfig config, String apiKey) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(config.timeout())
                .maxRetries(2);
        if (config.baseUrl() != null) {
            builder.baseUrl(config.baseUrl());
        }
        this.client = builder.build();
        this.model = config.model();
    }

    @Override
    public String name() {
        return "anthropic";
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
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(request.maxTokens())
                .system(request.system());
        for (LlmMessage m : request.messages()) {
            if ("assistant".equals(m.role())) {
                params.addAssistantMessage(m.content());
            } else {
                params.addUserMessage(m.content());
            }
        }
        long start = System.nanoTime();
        Message message;
        try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params.build())) {
            MessageAccumulator accumulator = MessageAccumulator.create();
            stream.stream().forEach(accumulator::accumulate);
            message = accumulator.message();
        } catch (AnthropicServiceException e) {
            throw new LlmException(LlmException.Kind.PROVIDER_ERROR, "Anthropic API error " + e.statusCode()
                    + e.requestId().map(id -> " (request " + id + ")").orElse(""), e);
        } catch (AnthropicIoException e) {
            throw new LlmException(LlmException.Kind.TIMEOUT, "Anthropic API unreachable or timed out: " + e.getMessage(), e);
        }
        String stop = message.stopReason().map(StopReason::asString).orElse("unknown");
        if (message.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            throw new LlmException(LlmException.Kind.REFUSED, "The model declined this request");
        }
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining());
        return new LlmResponse(name(), model, text, message.usage().inputTokens(), message.usage().outputTokens(), stop,
                message.stopReason().filter(StopReason.MAX_TOKENS::equals).isPresent(), (System.nanoTime() - start) / 1_000_000);
    }
}
