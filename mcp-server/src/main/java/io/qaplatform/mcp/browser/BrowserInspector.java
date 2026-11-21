package io.qaplatform.mcp.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import io.qaplatform.mcp.config.McpConfig;
import io.qaplatform.mcp.tools.ToolException;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Read-only browser inspection for writing and debugging locators. This is NOT test execution:
 * it opens one page in a throw-away headless Chromium, reports what it sees and closes it. Test
 * runs always go through the Java framework ({@code run_*} tools).
 *
 * <p>Safety: only origins of the configured application environments may be opened, and every
 * request to any other origin is aborted, so the tools cannot be used to browse or probe
 * arbitrary hosts. No credentials are entered; pages behind sign-in show their sign-in screen.
 */
public final class BrowserInspector {

    private static final int MAX_SNAPSHOT_CHARS = 30_000;
    private final Set<String> allowedOrigins;
    private final double timeoutMs;

    public BrowserInspector(McpConfig config) {
        this.allowedOrigins = config.browserOrigins();
        this.timeoutMs = config.browserTimeout().toMillis();
    }

    public Set<String> allowedOrigins() {
        return allowedOrigins;
    }

    /** Validates a URL against the allow-list (scheme, host and port must match exactly). */
    public String checkUrl(String url) {
        String origin;
        try {
            origin = McpConfig.origin(url);
        } catch (IllegalArgumentException e) {
            throw ToolException.invalid("url must be an absolute http(s) URL");
        }
        if (!origin.startsWith("http://") && !origin.startsWith("https://")) {
            throw ToolException.invalid("Only http and https URLs can be inspected");
        }
        if (!allowedOrigins.contains(origin)) {
            throw ToolException.forbidden("URL is outside the application under test. Allowed origins: " + allowedOrigins);
        }
        return url;
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("purpose", "Page inspection only. Tests run through the Java framework (run_* tools), not this browser.");
        status.put("allowedOrigins", allowedOrigins);
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            status.put("chromium", browser.version());
            status.put("available", true);
            browser.close();
        } catch (PlaywrightException e) {
            status.put("available", false);
            status.put("error", firstLine(e.getMessage()));
            status.put("fix", "Install browsers: mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args=\"install chromium\"");
        }
        return status;
    }

    public Map<String, Object> screenshot(String url, boolean fullPage) {
        return withPage(url, (page) -> {
            byte[] png = page.screenshot(new Page.ScreenshotOptions().setFullPage(fullPage));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("url", page.url());
            result.put("title", page.title());
            result.put("fullPage", fullPage);
            result.put("imageBase64", Base64.getEncoder().encodeToString(png));
            return result;
        });
    }

    public Map<String, Object> pageInformation(String url) {
        return withPage(url, (page) -> {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("url", page.url());
            info.put("title", page.title());
            info.put("headings", texts(page.locator("h1, h2, h3"), 30));
            info.put("landmarks", page.locator("header, nav, main, footer, aside, [role=banner], [role=navigation], [role=main]").count());
            info.put("forms", page.locator("form").count());
            info.put("inputs", page.locator("input, select, textarea").count());
            info.put("buttons", texts(page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON), 40));
            info.put("links", page.locator("a[href]").count());
            info.put("testIds", attributes(page.locator("[data-testid]"), "data-testid", 60));
            return info;
        });
    }

    /**
     * Accessibility snapshot (the structure Playwright's role/label locators match against),
     * optionally limited to a CSS-selected region.
     */
    public Map<String, Object> inspectElements(String url, String selector) {
        return withPage(url, (page) -> {
            Locator root = page.locator(selector == null ? "body" : selector).first();
            if (root.count() == 0) {
                throw ToolException.notFound("Selector matched nothing on the page: " + selector);
            }
            String snapshot = root.ariaSnapshot();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("url", page.url());
            result.put("selector", selector == null ? "body" : selector);
            result.put("ariaSnapshot", snapshot.length() > MAX_SNAPSHOT_CHARS ? snapshot.substring(0, MAX_SNAPSHOT_CHARS) + "\n... (truncated)" : snapshot);
            result.put("hint", "Prefer page.getByRole(<role>, name) / getByLabel / getByTestId over CSS, matching the framework's locator rules.");
            return result;
        });
    }

    private synchronized Map<String, Object> withPage(String url, Function<Page, Map<String, Object>> action) {
        checkUrl(url);
        List<String> consoleErrors = new ArrayList<>();
        List<String> blocked = new ArrayList<>();
        List<String> failedRequests = new ArrayList<>();
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             BrowserContext context = browser.newContext()) {
            context.setDefaultTimeout(timeoutMs);
            context.setDefaultNavigationTimeout(timeoutMs);
            context.route("**/*", route -> {
                String requestUrl = route.request().url();
                if (requestUrl.startsWith("data:") || isAllowed(requestUrl)) {
                    route.resume();
                } else {
                    blocked.add(requestUrl);
                    route.abort("blockedbyclient");
                }
            });
            Page page = context.newPage();
            page.onConsoleMessage(m -> {
                if ("error".equals(m.type())) {
                    consoleErrors.add(m.text());
                }
            });
            page.onRequestFailed(r -> failedRequests.add(r.method() + " " + r.url() + " " + r.failure()));
            Response response = page.navigate(url);
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("httpStatus", response == null ? null : response.status());
            result.putAll(action.apply(page));
            result.put("consoleErrors", consoleErrors);
            result.put("failedRequests", failedRequests.stream().filter(f -> blocked.stream().noneMatch(f::contains)).toList());
            result.put("blockedOffSiteRequests", blocked);
            return result;
        } catch (PlaywrightException e) {
            throw new ToolException("unavailable", "Browser inspection failed: " + firstLine(e.getMessage()));
        }
    }

    private boolean isAllowed(String url) {
        try {
            return allowedOrigins.contains(McpConfig.origin(url));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static List<String> texts(Locator locator, int max) {
        List<String> all = locator.allInnerTexts();
        return all.stream().map(String::strip).filter(s -> !s.isEmpty()).limit(max).toList();
    }

    private static List<String> attributes(Locator locator, String attribute, int max) {
        List<String> values = new ArrayList<>();
        int count = Math.min(locator.count(), max);
        for (int i = 0; i < count; i++) {
            String v = locator.nth(i).getAttribute(attribute);
            if (v != null) {
                values.add(v);
            }
        }
        return values;
    }

    private static String firstLine(String message) {
        return message == null ? "" : message.lines().findFirst().orElse("");
    }
}
