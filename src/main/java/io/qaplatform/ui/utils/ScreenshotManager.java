package io.qaplatform.ui.utils;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import io.qaplatform.core.reporting.AllureAttachments;

/** Screenshot capture that always lands in the Allure report. */
public final class ScreenshotManager {

    private ScreenshotManager() {
    }

    public static byte[] attachFullPage(Page page, String name) {
        if (page == null || page.isClosed()) {
            return null;
        }
        byte[] png = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
        AllureAttachments.png(name, png);
        return png;
    }

    public static byte[] attachElement(Locator locator, String name) {
        byte[] png = locator.screenshot();
        AllureAttachments.png(name, png);
        return png;
    }
}
