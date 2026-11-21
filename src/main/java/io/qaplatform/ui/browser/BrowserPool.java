package io.qaplatform.ui.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;
import io.qaplatform.core.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Worker-level reuse of the expensive objects.
 * <p>
 * Each TestNG worker thread lazily gets its own {@link Playwright} driver and one {@link Browser}
 * per browser type; these are reused by every scenario that thread runs. Scenarios never share
 * contexts or pages: those are created per scenario by {@link BrowserSession}.
 * Playwright objects are not thread-safe, so nothing here crosses threads except the shutdown registry.
 */
public final class BrowserPool {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserPool.class);
    private static final PlaywrightFactory FACTORY = new PlaywrightFactory(ConfigManager.get());

    private static final ThreadLocal<Playwright> PLAYWRIGHT = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, Browser>> BROWSERS = ThreadLocal.withInitial(HashMap::new);
    /** Every driver ever created, so the runner can close them after all workers finish. */
    private static final Queue<Playwright> CREATED = new ConcurrentLinkedQueue<>();

    private BrowserPool() {
    }

    static Browser browser(String browserName) {
        Browser browser = BROWSERS.get().get(browserName);
        if (browser != null && browser.isConnected()) {
            return browser;
        }
        long start = System.nanoTime();
        browser = FACTORY.launch(workerPlaywright(), browserName);
        BROWSERS.get().put(browserName, browser);
        LOG.info("Launched {} on {} in {} ms", browserName, Thread.currentThread().getName(),
                (System.nanoTime() - start) / 1_000_000);
        return browser;
    }

    static PlaywrightFactory factory() {
        return FACTORY;
    }

    /** The calling worker's Playwright driver; also used for API contexts so each worker runs one driver. */
    public static Playwright workerPlaywright() {
        Playwright pw = PLAYWRIGHT.get();
        if (pw == null) {
            pw = Playwright.create();
            PLAYWRIGHT.set(pw);
            CREATED.add(pw);
        }
        return pw;
    }

    /** Closes every browser and driver created by any worker. Called once after the suite. */
    public static void shutdownAll() {
        Playwright pw;
        while ((pw = CREATED.poll()) != null) {
            try {
                pw.close();
            } catch (RuntimeException e) {
                LOG.warn("Error closing Playwright: {}", e.getMessage());
            }
        }
    }
}
