package io.qaplatform.ai;

import com.sun.net.httpserver.HttpServer;
import io.qaplatform.ai.llm.AiConfig;
import io.qaplatform.ai.llm.LlmException;
import io.qaplatform.ai.llm.LlmFactory;
import io.qaplatform.ai.llm.LlmProvider;
import io.qaplatform.ai.llm.LlmRequest;
import io.qaplatform.ai.llm.LlmResponse;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wire-format tests: each provider talks to a local stub that records the request and answers in
 * that provider's response format (Anthropic: a server-sent-event stream consumed by the official SDK).
 */
public class LlmProvidersTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> headers = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();

    private String stub(String route, int status, String contentType, String response) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(route, exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            path.set(exchange.getRequestURI().toString());
            Map<String, String> h = new java.util.HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0)));
            headers.set(h);
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterMethod(alwaysRun = true)
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static AiConfig config(String provider, String model, String baseUrl) {
        Map<String, String> v = new java.util.HashMap<>();
        v.put("qa.ai.provider", provider);
        if (model != null) {
            v.put("qa.ai.model", model);
        }
        v.put("qa.ai.base.url", baseUrl);
        v.put("qa.ai.timeout.seconds", "20");
        return AiConfig.from(v::get);
    }

    private static LlmRequest request(boolean json) {
        return LlmRequest.of("test", "You are a tester.", "Say hi", 256, json);
    }

    @Test
    public void openAiCompatibleLocalServer() throws Exception {
        String base = stub("/v1/chat/completions", 200, "application/json", """
                {"model":"llama3","choices":[{"message":{"role":"assistant","content":"{\\"ok\\":true}"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":12,"completion_tokens":5}}""");
        LlmProvider local = LlmFactory.create(config("local", "llama3", base + "/v1"), null);
        LlmResponse r = local.complete(request(true));
        assertThat(r.text()).isEqualTo("{\"ok\":true}");
        assertThat(r.inputTokens()).isEqualTo(12);
        assertThat(r.outputTokens()).isEqualTo(5);
        JsonNode sent = JSON.readTree(body.get());
        assertThat(sent.path("model").asString()).isEqualTo("llama3");
        assertThat(sent.path("messages").path(0).path("role").asString()).isEqualTo("system");
        assertThat(sent.has("max_tokens")).isTrue();
        assertThat(sent.path("response_format").path("type").asString()).isEqualTo("json_object");
        assertThat(headers.get()).doesNotContainKey("authorization");
    }

    @Test
    public void openAiUsesBearerKeyAndMaxCompletionTokens() throws Exception {
        String base = stub("/v1/chat/completions", 200, "application/json", """
                {"model":"m","choices":[{"message":{"content":"hi"},"finish_reason":"length"}],"usage":{"prompt_tokens":1,"completion_tokens":2}}""");
        LlmResponse r = LlmFactory.create(config("openai", "some-model", base + "/v1"), "sk-test-key").complete(request(false));
        assertThat(r.truncated()).isTrue();
        assertThat(headers.get()).containsEntry("authorization", "Bearer sk-test-key");
        JsonNode sent = JSON.readTree(body.get());
        assertThat(sent.has("max_completion_tokens")).isTrue();
        assertThat(sent.has("response_format")).isFalse();
    }

    @Test
    public void geminiSendsKeyInHeaderNotUrl() throws Exception {
        String base = stub("/v1beta/models/", 200, "application/json", """
                {"candidates":[{"content":{"parts":[{"text":"hel"},{"text":"lo"}]},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":3}}""");
        LlmResponse r = LlmFactory.create(config("gemini", "gemini-model", base + "/v1beta"), "g-key").complete(request(true));
        assertThat(r.text()).isEqualTo("hello");
        assertThat(r.inputTokens()).isEqualTo(7);
        assertThat(path.get()).isEqualTo("/v1beta/models/gemini-model:generateContent").doesNotContain("g-key");
        assertThat(headers.get()).containsEntry("x-goog-api-key", "g-key");
        JsonNode sent = JSON.readTree(body.get());
        assertThat(sent.path("systemInstruction").path("parts").path(0).path("text").asString()).isEqualTo("You are a tester.");
        assertThat(sent.path("generationConfig").path("responseMimeType").asString()).isEqualTo("application/json");
    }

    @Test
    public void anthropicThroughOfficialSdkStreaming() throws Exception {
        String sse = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":21,"output_tokens":1}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello "}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"tester"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":9}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        String base = stub("/v1/messages", 200, "text/event-stream", sse);
        LlmProvider claude = LlmFactory.create(config("anthropic", null, base), "test-anthropic-key");
        assertThat(claude.model()).isEqualTo("claude-opus-5-5");
        LlmResponse r = claude.complete(request(false));
        assertThat(r.text()).isEqualTo("Hello tester");
        assertThat(r.inputTokens()).isEqualTo(21);
        assertThat(r.outputTokens()).isEqualTo(9);
        assertThat(r.stopReason()).isEqualTo("end_turn");
        // SDK 3.x authenticates with a bearer header and pins the API version.
        assertThat(headers.get()).containsEntry("authorization", "Bearer test-anthropic-key").containsKey("anthropic-version");
        JsonNode sent = JSON.readTree(body.get());
        assertThat(sent.path("model").asString()).isEqualTo("claude-opus-5-5");
        assertThat(sent.path("stream").asBoolean()).isTrue();
        assertThat(sent.path("system").asString()).isEqualTo("You are a tester.");
        assertThat(sent.path("messages").path(0).path("content").asString()).isEqualTo("Say hi");
        assertThat(sent.has("thinking")).as("claude-opus-5-5 always thinks adaptively; no thinking parameter is sent").isFalse();
    }

    @Test
    public void providerErrorsAreTypedAndRedacted() throws Exception {
        String base = stub("/v1/chat/completions", 500, "application/json", "{\"error\":\"boom\",\"echo\":\"Authorization: Bearer abcdefghijklmnop\"}");
        LlmProvider local = LlmFactory.create(config("local", "llama3", base + "/v1"), null);
        assertThatThrownBy(() -> local.complete(request(false)))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("500")
                .hasMessageNotContaining("abcdefghijklmnop")
                .satisfies(e -> assertThat(((LlmException) e).kind()).isEqualTo(LlmException.Kind.PROVIDER_ERROR));
    }

    @Test
    public void missingSettingsDisableAiWithAReason() {
        assertThat(LlmFactory.create(AiConfig.from(k -> null), null).enabled()).isFalse();
        LlmProvider noKey = LlmFactory.create(config("anthropic", null, "http://x"), null);
        assertThat(noKey.enabled()).isFalse();
        assertThat(noKey.disabledReason()).contains("ANTHROPIC_API_KEY");
        LlmProvider noModel = LlmFactory.create(config("openai", null, "http://x"), "k");
        assertThat(noModel.disabledReason()).contains("QA_AI_MODEL");
        assertThatThrownBy(() -> AiConfig.from(k -> k.equals("qa.ai.provider") ? "skynet" : null)).hasMessageContaining("must be one of");
    }
}
