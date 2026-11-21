package io.qaplatform.ui.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import io.qaplatform.core.config.ConfigManager;
import io.qaplatform.core.exceptions.FrameworkException;

import java.nio.file.Path;
import java.util.Locale;

/** Stateless construction of browsers and contexts from configuration. */
public final class PlaywrightFactory {

    public static final Path ARTIFACTS = Path.of("target", "artifacts");

    private final ConfigManager config;

    public PlaywrightFactory(ConfigManager config) {
        this.config = config;
    }

    public Browser launch(Playwright playwright, String browserName) {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(config.headless())
                .setSlowMo(config.getInt("slow.mo.ms", 0));
        return switch (browserName.toLowerCase(Locale.ROOT)) {
            case "chromium" -> playwright.chromium().launch(options);
            case "chrome" -> playwright.chromium().launch(options.setChannel("chrome"));
            case "msedge" -> playwright.chromium().launch(options.setChannel("msedge"));
            case "firefox" -> playwright.firefox().launch(options);
            case "webkit" -> playwright.webkit().launch(options);
            default -> throw new FrameworkException("Unsupported browser '" + browserName
                    + "'. Use chromium, chrome, msedge, firefox or webkit.");
        };
    }

    /** A fresh, isolated context: own cookies, storage, cache and permissions. */
    public BrowserContext newContext(Browser browser, String storageStateJson) {
        Browser.NewContextOptions options = new Browser.NewContextOptions()
                .setBaseURL(config.baseUrl())
                .setViewportSize(config.getInt("viewport.width", 1440), config.getInt("viewport.height", 900))
                .setLocale(config.get("locale", "en-US"))
                .setAcceptDownloads(true)
                .setIgnoreHTTPSErrors(config.getBool("ignore.https.errors", false));
        if (config.recordVideo()) {
            options.setRecordVideoDir(ARTIFACTS.resolve("videos"));
        }
        if (storageStateJson != null) {
            options.setStorageState(storageStateJson);
        }
        BrowserContext context = browser.newContext(options);
        context.setDefaultTimeout(config.defaultTimeoutMs());
        context.setDefaultNavigationTimeout(config.getInt("timeout.navigation.ms", 30_000));
        return context;
    }
}
