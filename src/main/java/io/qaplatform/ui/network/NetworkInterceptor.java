package io.qaplatform.ui.network;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Route;
import io.qameta.allure.Allure;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Network interception at the context level: stubbing, failure injection, resource blocking
 * and request capture. Context-level routes also apply to popups and new pages.
 */
public final class NetworkInterceptor {

    private final BrowserContext context;

    public NetworkInterceptor(BrowserContext context) {
        this.context = context;
    }

    /** Replies to matching requests with a canned JSON body. */
    public NetworkInterceptor stubJson(String urlGlob, int status, String jsonBody) {
        Allure.step("Stub " + urlGlob + " → " + status);
        context.route(urlGlob, route -> route.fulfill(new Route.FulfillOptions()
                .setStatus(status)
                .setContentType("application/json")
                .setBody(jsonBody)));
        return this;
    }

    /** Simulates a network-level failure (connection refused, timeout, ...). */
    public NetworkInterceptor fail(String urlGlob, String errorCode) {
        Allure.step("Abort " + urlGlob + " with " + errorCode);
        context.route(urlGlob, route -> route.abort(errorCode));
        return this;
    }

    /** Blocks images/fonts/analytics to speed up tests that do not need them. */
    public NetworkInterceptor blockResources(List<String> resourceTypes) {
        context.route("**/*", route -> {
            if (resourceTypes.contains(route.request().resourceType())) {
                route.abort();
            } else {
                route.fallback();
            }
        });
        return this;
    }

    /** Adds headers (e.g. a correlation id or feature flag) to every matching request. */
    public NetworkInterceptor addHeaders(String urlGlob, Map<String, String> headers) {
        context.route(urlGlob, route -> {
            Map<String, String> merged = new java.util.HashMap<>(route.request().headers());
            merged.putAll(headers);
            route.fallback(new Route.FallbackOptions().setHeaders(merged));
        });
        return this;
    }

    /** Records URLs of requests matching the glob, for later assertions. */
    public List<String> capture(String urlSubstring) {
        List<String> seen = new CopyOnWriteArrayList<>();
        context.onRequest(r -> {
            if (r.url().contains(urlSubstring)) {
                seen.add(r.method() + " " + r.url());
            }
        });
        return seen;
    }

    public void clear() {
        context.unrouteAll();
    }
}
