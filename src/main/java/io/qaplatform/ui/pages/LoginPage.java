package io.qaplatform.ui.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import io.qameta.allure.Allure;
import io.qameta.allure.Step;

public class LoginPage extends BasePage {

    public LoginPage(Page page) {
        super(page);
    }

    @Override
    protected String path() {
        return "/login";
    }

    @Step("Open the login page")
    public LoginPage open() {
        navigate();
        return this;
    }

    /**
     * Submits credentials. Uses {@code Allure.step(name, lambda)} rather than {@code @Step} because
     * {@code @Step} records every method argument (even "masked" ones) in the raw report JSON.
     */
    public void signIn(String username, String password) {
        Allure.step("Sign in as '" + username + "'", () -> {
            field("Username").fill(username);
            field("Password").fill(password);
            button("Sign in").click();
        });
    }

    public Locator error() {
        return page.getByRole(AriaRole.ALERT);
    }
}
