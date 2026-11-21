package io.qaplatform.ui.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import io.qameta.allure.Step;

public class DashboardPage extends BasePage {

    public DashboardPage(Page page) {
        super(page);
    }

    @Override
    protected String path() {
        return "/dashboard";
    }

    @Step("Open the dashboard")
    public DashboardPage open() {
        navigate();
        return this;
    }

    public Locator welcome() {
        return page.getByTestId("welcome");
    }

    public Locator userCount() {
        return page.getByTestId("user-count");
    }

    public Locator loadError() {
        return page.getByTestId("load-error");
    }
}
