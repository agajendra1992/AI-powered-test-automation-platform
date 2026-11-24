package io.qaplatform.ui.pages;

import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import io.qaplatform.ui.browser.PlaywrightFactory;
import io.qaplatform.ui.components.HeaderComponent;

import java.nio.file.Path;
import java.util.List;

/**
 * Minimal shared behaviour for page objects. Pages expose business actions and state
 * (locators / values); assertions live in step definitions. Reusable widgets are
 * composed in as components rather than inherited.
 */
public abstract class BasePage {

    protected final Page page;

    protected BasePage(Page page) {
        this.page = page;
    }

    /** Path relative to {@code base.url}. */
    protected abstract String path();

    protected void navigate() {
        page.navigate(path());
    }

    public HeaderComponent header() {
        return new HeaderComponent(page);
    }

    public Locator heading() {
        return page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setLevel(1));
    }

    /** Regions whose content changes between runs (counts, dates); masked in visual checks. None by default. */
    public List<Locator> dynamicRegions() {
        return List.of();
    }

    protected Locator button(String name) {
        return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(name).setExact(true));
    }

    protected Locator field(String label) {
        return page.getByLabel(label, new Page.GetByLabelOptions().setExact(true));
    }

    /** Runs the trigger, waits for the download and saves it under target/artifacts/downloads. */
    protected Path download(Runnable trigger) {
        Download d = page.waitForDownload(trigger);
        Path target = PlaywrightFactory.ARTIFACTS.resolve("downloads").resolve(System.nanoTime() + "-" + d.suggestedFilename());
        d.saveAs(target);
        return target;
    }
}
