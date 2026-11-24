package io.qaplatform.ui.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import io.qameta.allure.Step;

import java.util.List;

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

    /**
     * The user count changes as other scenarios create users. The whole line is masked, not just the number:
     * a mask is drawn over the element's box, and the number's box grows with its digits.
     */
    @Override
    public List<Locator> dynamicRegions() {
        return List.of(page.locator("p").filter(new Locator.FilterOptions().setHas(userCount())));
    }

    public Locator loadError() {
        return page.getByTestId("load-error");
    }
}
