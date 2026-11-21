package io.qaplatform.bdd.context;

import com.microsoft.playwright.Page;
import io.cucumber.java.Scenario;
import io.qaplatform.api.auth.AuthProvider;
import io.qaplatform.api.auth.BearerTokenAuth;
import io.qaplatform.api.client.ApiClient;
import io.qaplatform.api.responses.ApiResponse;
import io.qaplatform.api.services.AuthService;
import io.qaplatform.api.services.PaymentService;
import io.qaplatform.api.services.UserService;
import io.qaplatform.core.config.ConfigManager;
import io.qaplatform.core.exceptions.FrameworkException;
import io.qaplatform.core.reporting.AllureAttachments;
import io.qaplatform.ui.browser.AuthState;
import io.qaplatform.ui.browser.BrowserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one scenario owns. PicoContainer creates a new instance per scenario and
 * injects the same instance into all step and hook classes of that scenario, so parallel
 * scenarios can never see each other's browser, API clients, tokens or data.
 * <p>
 * Resources are created lazily: an API-only scenario never starts a browser.
 */
public class ScenarioContext {

    private static final Logger LOG = LoggerFactory.getLogger(ScenarioContext.class);

    private final ConfigManager config = ConfigManager.get();
    private final List<ApiClient> clients = new ArrayList<>();
    private final Deque<Runnable> cleanup = new ArrayDeque<>();
    private final Map<String, Object> data = new HashMap<>();
    private final List<String> apiCalls = new ArrayList<>();

    private Scenario scenario;
    private String scenarioId;
    private boolean retry;
    private BrowserSession browser;
    private String browserStorageState;
    private String adminToken;
    private ApiClient adminClient;
    private ApiResponse lastResponse;

    // ------------------------------------------------------------------ lifecycle (called by hooks)

    public void start(Scenario s, boolean isRetry) {
        this.scenario = s;
        this.scenarioId = io.qaplatform.core.listeners.ScenarioIds.id(s.getUri(), s.getLine());
        this.retry = isRetry;
    }

    /** Runs registered cleanup (LIFO) even after failures, then releases browser and API resources. */
    public void finish(boolean failed) {
        while (!cleanup.isEmpty()) {
            try {
                cleanup.pop().run();
            } catch (RuntimeException e) {
                LOG.warn("Cleanup step failed in '{}': {}", name(), e.getMessage());
            }
        }
        if (browser != null) {
            browser.close(failed);
            browser = null;
        }
        if (failed && !apiCalls.isEmpty()) {
            AllureAttachments.text("API calls in this scenario", String.join("\n", apiCalls));
        }
        clients.forEach(ApiClient::close);
        clients.clear();
    }

    public String name() {
        return scenario == null ? "unknown" : scenario.getName();
    }

    public String id() {
        return scenarioId;
    }

    public Collection<String> tags() {
        return scenario.getSourceTagNames();
    }

    // ------------------------------------------------------------------ browser

    /** Pre-authenticates the browser with a token; must be called before the first {@link #page()}. */
    public void authenticateBrowserWithToken(String token) {
        if (browser != null) {
            throw new FrameworkException("Browser already started; set authentication before opening pages");
        }
        this.browserStorageState = AuthState.withLocalStorageToken(token);
    }

    public Page page() {
        if (browser == null) {
            browser = BrowserSession.open(config.browser(), name(), browserStorageState, retry);
        }
        return browser.page();
    }

    public BrowserSession browser() {
        page();
        return browser;
    }

    // ------------------------------------------------------------------ API

    /** New API client with the given auth; disposed automatically at scenario end. */
    public ApiClient apiClient(AuthProvider auth) {
        ApiClient c = new ApiClient(config.apiBaseUrl(), auth, r -> apiCalls.add(
                r.request() + " -> " + r.status() + " (" + r.durationMs() + " ms, correlation " + r.correlationId() + ")"));
        clients.add(c);
        return c;
    }

    /** Bearer token of the configured administrator, fetched once per scenario. */
    public String adminToken() {
        if (adminToken == null) {
            adminToken = new AuthService(apiClient(null)).token(config.get("auth.username"), config.get("auth.password"));
        }
        return adminToken;
    }

    private ApiClient adminClient() {
        if (adminClient == null) {
            adminClient = apiClient(BearerTokenAuth.of(adminToken()));
        }
        return adminClient;
    }

    public UserService users() {
        return new UserService(adminClient());
    }

    public PaymentService payments() {
        return new PaymentService(adminClient());
    }

    public AuthService auth() {
        return new AuthService(apiClient(null));
    }

    public void lastResponse(ApiResponse response) {
        this.lastResponse = response;
    }

    public ApiResponse lastResponse() {
        if (lastResponse == null) {
            throw new FrameworkException("No API response recorded yet in this scenario");
        }
        return lastResponse;
    }

    // ------------------------------------------------------------------ data + cleanup

    public void onCleanup(Runnable action) {
        cleanup.push(action);
    }

    public <T> void put(Key<T> key, T value) {
        data.put(key.name(), value);
    }

    public <T> T get(Key<T> key) {
        Object v = data.get(key.name());
        if (v == null) {
            throw new FrameworkException("Scenario data '" + key.name() + "' was not set by an earlier step");
        }
        return key.type().cast(v);
    }

    /** Typed key for scenario data, so steps share values without casts or string typos. */
    public record Key<T>(String name, Class<T> type) {
    }
}
