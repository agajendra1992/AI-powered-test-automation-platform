package io.qaplatform.api.client;

import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import io.qaplatform.core.config.ConfigManager;
import io.qaplatform.ui.browser.BrowserPool;

import java.util.Map;

/**
 * Creates {@link APIRequestContext}s on the worker's shared Playwright driver (no browser launched).
 * The caller owns the returned context and must dispose it, normally via {@link ApiClient#close()}.
 */
public final class ApiContextFactory {

    private ApiContextFactory() {
    }

    public static APIRequestContext newContext(String baseUrl, Map<String, String> defaultHeaders) {
        return BrowserPool.workerPlaywright().request().newContext(new APIRequest.NewContextOptions()
                .setBaseURL(baseUrl)
                .setExtraHTTPHeaders(defaultHeaders)
                .setTimeout(ConfigManager.get().apiTimeoutMs())
                .setIgnoreHTTPSErrors(ConfigManager.get().getBool("ignore.https.errors", false)));
    }
}
