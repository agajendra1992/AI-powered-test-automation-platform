package io.qaplatform.ui.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;
import io.qaplatform.core.config.ConfigManager;
import io.qaplatform.core.config.ConfigManager.TraceMode;
import io.qaplatform.core.reporting.AllureAttachments;
import io.qaplatform.ui.utils.ScreenshotManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Everything browser-related for exactly one scenario: an isolated context, its page,
 * tracing, and collected diagnostics (console errors, page errors, failed requests, dialogs).
 * Owned by the scenario-scoped {@code ScenarioContext}; never shared across threads.
 */
public final class BrowserSession {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserSession.class);

    private final String browserName;
    private final String scenarioName;
    private final BrowserContext context;
    private final Page page;
    private final boolean tracing;
    private final List<String> consoleErrors = new CopyOnWriteArrayList<>();
    private final List<String> pageErrors = new CopyOnWriteArrayList<>();
    private final List<String> failedRequests = new CopyOnWriteArrayList<>();
    private final List<String> dialogs = new CopyOnWriteArrayList<>();

    private BrowserSession(String browserName, String scenarioName, String storageState, boolean isRetry) {
        this.browserName = browserName;
        this.scenarioName = scenarioName;
        this.context = BrowserPool.factory().newContext(BrowserPool.browser(browserName), storageState);
        TraceMode mode = ConfigManager.get().traceMode();
        this.tracing = mode == TraceMode.ON || mode == TraceMode.RETAIN_ON_FAILURE
                || (mode == TraceMode.ON_FIRST_RETRY && isRetry);
        if (tracing) {
            context.tracing().start(new Tracing.StartOptions()
                    .setTitle(scenarioName).setScreenshots(true).setSnapshots(true).setSources(false));
        }
        this.page = context.newPage();
        wireDiagnostics();
    }

    /**
     * @param storageState optional Playwright storage state (see {@link AuthState}) for a pre-authenticated context
     * @param isRetry      whether this scenario is being retried (drives {@code on-first-retry} tracing)
     */
    public static BrowserSession open(String browserName, String scenarioName, String storageState, boolean isRetry) {
        return new BrowserSession(browserName, scenarioName, storageState, isRetry);
    }

    private void wireDiagnostics() {
        page.onConsoleMessage(m -> {
            if ("error".equals(m.type())) {
                consoleErrors.add(m.text());
            }
        });
        page.onPageError(pageErrors::add);
        page.onRequestFailed(r -> failedRequests.add(r.method() + " " + r.url() + " → " + r.failure()));
        page.onResponse(r -> {
            if (r.status() >= 500) {
                failedRequests.add(r.request().method() + " " + r.url() + " → HTTP " + r.status());
            }
        });
        String policy = ConfigManager.get().get("ui.dialog.policy", "accept").toLowerCase(Locale.ROOT);
        page.onDialog(d -> {
            dialogs.add(d.type() + ": " + d.message());
            if ("dismiss".equals(policy)) {
                d.dismiss();
            } else {
                d.accept();
            }
        });
    }

    public Page page() {
        return page;
    }

    public BrowserContext context() {
        return context;
    }

    public String browserName() {
        return browserName;
    }

    public List<String> consoleErrors() {
        return List.copyOf(consoleErrors);
    }

    public List<String> failedRequests() {
        return List.copyOf(failedRequests);
    }

    /** Captures evidence (always on failure, trace per mode) and closes the context. Never throws. */
    public void close(boolean failed) {
        String safe = scenarioName.replaceAll("[^a-zA-Z0-9._-]", "_");
        try {
            if (failed) {
                ScreenshotManager.attachFullPage(page, "Failure screenshot");
                attachDiagnostics();
            }
            stopTracing(safe, failed);
            com.microsoft.playwright.Video recording = page.video();
            Path video = recording != null ? recording.path() : null;
            context.close();
            if (video != null && (failed || ConfigManager.get().getBool("video.keep.on.pass", false))) {
                AllureAttachments.file("Video", "video/webm", video, ".webm");
            }
        } catch (RuntimeException e) {
            LOG.warn("Evidence capture/close failed for '{}': {}", scenarioName, e.getMessage());
            try {
                context.close();
            } catch (RuntimeException closeError) {
                LOG.debug("Context already closed: {}", closeError.getMessage());
            }
        }
    }

    private void attachDiagnostics() {
        StringBuilder sb = new StringBuilder();
        append(sb, "Console errors", consoleErrors);
        append(sb, "Page errors", pageErrors);
        append(sb, "Failed / 5xx requests", failedRequests);
        append(sb, "Dialogs", dialogs);
        sb.append("URL at failure: ").append(page.url()).append('\n');
        AllureAttachments.text("Browser diagnostics", sb.toString());
    }

    private static void append(StringBuilder sb, String title, List<String> items) {
        sb.append(title).append(" (").append(items.size()).append(")\n");
        items.forEach(i -> sb.append("  - ").append(i).append('\n'));
    }

    private void stopTracing(String safeName, boolean failed) {
        if (!tracing) {
            return;
        }
        TraceMode mode = ConfigManager.get().traceMode();
        if (failed || mode == TraceMode.ON || mode == TraceMode.ON_FIRST_RETRY) {
            Path trace = PlaywrightFactory.ARTIFACTS.resolve("traces").resolve(safeName + "-" + System.nanoTime() + ".zip");
            context.tracing().stop(new Tracing.StopOptions().setPath(trace));
            AllureAttachments.file("Playwright trace (npx playwright show-trace <file>)", "application/zip", trace, ".zip");
            LOG.info("Trace saved: {}", trace.toAbsolutePath());
        } else {
            context.tracing().stop();
        }
    }
}
