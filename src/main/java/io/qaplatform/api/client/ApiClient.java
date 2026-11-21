package io.qaplatform.api.client;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.options.RequestOptions;
import io.qameta.allure.Allure;
import io.qaplatform.api.auth.AuthProvider;
import io.qaplatform.api.auth.NoAuth;
import io.qaplatform.api.requests.ApiRequest;
import io.qaplatform.api.responses.ApiResponse;
import io.qaplatform.core.reporting.AllureAttachments;
import io.qaplatform.core.reporting.ApiMetrics;
import io.qaplatform.core.reporting.Redactor;
import io.qaplatform.core.reporting.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Generic HTTP client over Playwright's {@link APIRequestContext}.
 * Adds auth, a correlation id, timing, logging and Allure evidence to every call.
 * Services build on top of this; tests normally talk to services, not to the client.
 */
public class ApiClient implements AutoCloseable {

    public static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final Logger LOG = LoggerFactory.getLogger(ApiClient.class);
    private static final int MAX_ATTACHMENT_CHARS = 50_000;

    private final APIRequestContext context;
    private final AuthProvider defaultAuth;
    private final Consumer<ApiResponse> observer;

    public ApiClient(String baseUrl, AuthProvider defaultAuth) {
        this(baseUrl, defaultAuth, r -> { });
    }

    /** @param observer notified of every response (e.g. to keep a per-scenario call log) */
    public ApiClient(String baseUrl, AuthProvider defaultAuth, Consumer<ApiResponse> observer) {
        this.context = ApiContextFactory.newContext(baseUrl, Map.of("Accept", "application/json"));
        this.defaultAuth = defaultAuth == null ? new NoAuth() : defaultAuth;
        this.observer = observer;
    }

    public ApiResponse send(ApiRequest request) {
        String correlationId = request.headers().getOrDefault(CORRELATION_HEADER, UUID.randomUUID().toString());
        RequestOptions options = RequestOptions.create()
                .setMethod(request.method().name())
                .setHeader(CORRELATION_HEADER, correlationId);

        AuthProvider auth = request.authOverride() != null ? request.authOverride() : defaultAuth;
        auth.headers().forEach(options::setHeader);
        request.headers().forEach(options::setHeader);
        request.queryParams().forEach(options::setQueryParam);

        String bodyText = null;
        if (request.body() != null) {
            bodyText = request.body() instanceof String s ? s : Json.write(request.body());
            options.setHeader("Content-Type", "application/json").setData(bodyText);
        }

        String label = request.method() + " " + request.resolvedPath();
        String finalBody = bodyText;
        return Allure.step(label, () -> {
            MDC.put("correlationId", correlationId);
            try {
                long start = System.nanoTime();
                APIResponse raw = context.fetch(request.resolvedPath(), options);
                long ms = (System.nanoTime() - start) / 1_000_000;
                ApiResponse response;
                try {
                    response = new ApiResponse(label, raw.status(), raw.headers(), raw.text(), ms, correlationId);
                } finally {
                    raw.dispose();
                }
                ApiMetrics.record(request.method() + " " + request.pathTemplate(), ms);
                LOG.info("{} -> {} in {} ms", label, response.status(), ms);
                attach(request, finalBody, response);
                observer.accept(response);
                return response;
            } finally {
                MDC.remove("correlationId");
            }
        });
    }

    private static void attach(ApiRequest request, String body, ApiResponse response) {
        StringBuilder req = new StringBuilder(request.toString()).append('\n');
        request.queryParams().forEach((k, v) -> req.append("?").append(k).append('=').append(v).append('\n'));
        Redactor.headers(request.headers()).forEach((k, v) -> req.append(k).append(": ").append(v).append('\n'));
        if (request.authOverride() != null || !request.headers().isEmpty()) {
            req.append("(auth headers are redacted)\n");
        }
        req.append(CORRELATION_HEADER).append(": ").append(response.correlationId()).append('\n');
        if (body != null) {
            req.append('\n').append(truncate(Redactor.text(body)));
        }
        AllureAttachments.text("Request", req.toString());
        AllureAttachments.json("Response " + response.status() + " (" + response.durationMs() + " ms)",
                truncate(Redactor.text(response.body())));
    }

    private static String truncate(String s) {
        return s == null || s.length() <= MAX_ATTACHMENT_CHARS ? s : s.substring(0, MAX_ATTACHMENT_CHARS) + "\n…[truncated]";
    }

    /** Releases the underlying APIRequestContext. */
    @Override
    public void close() {
        context.dispose();
    }
}
