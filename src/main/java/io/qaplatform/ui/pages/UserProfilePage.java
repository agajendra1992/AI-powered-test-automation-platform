package io.qaplatform.ui.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import io.qameta.allure.Step;
import io.qaplatform.ui.utils.WaitUtils;

public class UserProfilePage extends BasePage {

    private final String userId;

    public UserProfilePage(Page page, String userId) {
        super(page);
        this.userId = userId;
    }

    @Override
    protected String path() {
        return "/users/" + userId;
    }

    @Step("Open user profile")
    public UserProfilePage open() {
        navigate();
        return this;
    }

    public Locator name() { return page.getByTestId("user-name"); }
    public Locator email() { return page.getByTestId("user-email"); }
    public Locator job() { return page.getByTestId("user-job"); }
    public Locator saveStatus() { return page.getByTestId("save-status"); }

    /** Edits the profile and returns once the backend has acknowledged the update. */
    @Step("Update profile: name='{newName}', job='{newJob}'")
    public UserProfilePage update(String newName, String newJob) {
        field("Name").fill(newName);
        field("Job").fill(newJob);
        WaitUtils.actionAndWaitForResponse(page, "PUT", "/api/users/" + userId, () -> button("Save changes").click());
        return this;
    }
}
