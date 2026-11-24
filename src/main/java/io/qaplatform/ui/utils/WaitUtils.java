package io.qaplatform.ui.utils;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.LoadState;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Explicit, condition-based waits for the cases Playwright's auto-waiting does not cover.
 * There is deliberately no fixed-sleep helper.
 */
public final class WaitUtils {

    private WaitUtils() {
    }

    /** Runs an action and returns the first response whose URL contains {@code urlPart} and matches the method. */
    public static Response actionAndWaitForResponse(Page page, String method, String urlPart, Runnable action) {
        Predicate<Response> match = r -> r.url().contains(urlPart) && r.request().method().equalsIgnoreCase(method);
        return page.waitForResponse(match, action);
    }

    public static void networkIdle(Page page) {
        page.waitForLoadState(LoadState.NETWORKIDLE);
    }

    /**
     * Waits until the page has finished rendering for a snapshot (screenshot or accessibility scan):
     * no network requests for 500 ms and web fonts loaded. Use before visual and accessibility checks only.
     */
    public static void stableRender(Page page) {
        page.waitForLoadState(LoadState.NETWORKIDLE);
        page.evaluate("() => document.fonts.ready.then(() => true)");
    }

    /** Polls a non-UI condition (e.g. eventual consistency in a backend) with a timeout. */
    public static void until(String description, Duration timeout, Duration interval, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                // CHECKSTYLE.OFF: polling interval for non-UI eventual consistency, bounded by timeout
                Thread.sleep(interval.toMillis());
                // CHECKSTYLE.ON
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for: " + description, e);
            }
        }
        throw new AssertionError("Timed out after " + timeout.toSeconds() + "s waiting for: " + description);
    }
}
