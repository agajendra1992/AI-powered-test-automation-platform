package io.qaplatform.mcp.tools;

import io.qaplatform.mcp.browser.BrowserInspector;
import io.qaplatform.mcp.security.Role;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Browser inspection tools (headless Chromium, allow-listed origins only). They help write
 * locators and look at the application; they never run or affect tests.
 */
public final class BrowserTools {

    private static final Pattern URL = Pattern.compile("https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]+");
    private static final String PREFIX = "INSPECTION ONLY (not test execution). ";

    private final BrowserInspector inspector;

    public BrowserTools(BrowserInspector inspector) {
        this.inspector = inspector;
    }

    public List<ToolSpec> tools() {
        return List.of(
                new ToolSpec("get_browser_status", "Get browser status",
                        PREFIX + "Whether headless Chromium can start for inspection, and which origins may be opened.",
                        Role.VIEWER, true, Schema.object(), (p, a) -> ToolResult.of(inspector.status())),
                new ToolSpec("capture_page_screenshot", "Capture page screenshot",
                        PREFIX + "Opens a page of the application under test and returns a screenshot.",
                        Role.VIEWER, true,
                        Schema.object().string("url", "Absolute URL on an allowed origin", true, 500)
                                .bool("full_page", "Capture the whole scrollable page", false),
                        (p, a) -> {
                            Map<String, Object> result = new LinkedHashMap<>(inspector.screenshot(url(a), a.bool("full_page", false)));
                            String image = (String) result.remove("imageBase64");
                            return ToolResult.withImage(result, image);
                        }),
                new ToolSpec("get_page_information", "Get page information",
                        PREFIX + "Title, headings, forms, buttons, links, data-testid values, console errors and failed requests of a page.",
                        Role.VIEWER, true,
                        Schema.object().string("url", "Absolute URL on an allowed origin", true, 500),
                        (p, a) -> ToolResult.of(inspector.pageInformation(url(a)))),
                new ToolSpec("inspect_page_elements", "Inspect page elements",
                        PREFIX + "Accessibility snapshot (roles and accessible names Playwright locators match) of a page or a CSS-selected region.",
                        Role.VIEWER, true,
                        Schema.object().string("url", "Absolute URL on an allowed origin", true, 500)
                                .string("selector", "Optional CSS selector to limit the snapshot", false, 200),
                        (p, a) -> ToolResult.of(inspector.inspectElements(url(a), a.optionalString("selector", 200, Args.TEXT)))));
    }

    private String url(Args a) {
        return inspector.checkUrl(a.string("url", 500, URL));
    }
}
