package io.qaplatform.ui.components;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import io.qameta.allure.Step;

/** Application header: main navigation, signed-in user and logout. */
public class HeaderComponent {

    private final Locator root;

    public HeaderComponent(Page page) {
        this.root = page.getByTestId("app-header");
    }

    public Locator root() {
        return root;
    }

    public Locator signedInUser() {
        return root.getByTestId("signed-in-user");
    }

    @Step("Navigate to '{section}'")
    public void navigateTo(String section) {
        root.getByRole(AriaRole.NAVIGATION, new Locator.GetByRoleOptions().setName("Main"))
                .getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName(section).setExact(true))
                .click();
    }

    @Step("Log out")
    public void logout() {
        root.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Log out")).click();
    }
}
