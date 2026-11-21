package io.qaplatform.ui.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import io.qameta.allure.Step;
import io.qaplatform.ui.components.ModalComponent;
import io.qaplatform.ui.components.SearchBoxComponent;
import io.qaplatform.ui.components.TableComponent;

public class UserManagementPage extends BasePage {

    private final TableComponent table;
    private final SearchBoxComponent search;

    public UserManagementPage(Page page) {
        super(page);
        this.table = new TableComponent(page.getByRole(AriaRole.TABLE, new Page.GetByRoleOptions().setName("Users")));
        this.search = new SearchBoxComponent(page, "Search users");
    }

    @Override
    protected String path() {
        return "/users";
    }

    @Step("Open user management")
    public UserManagementPage open() {
        navigate();
        return this;
    }

    @Step("Search users for '{text}'")
    public UserManagementPage searchFor(String text) {
        search.search(text);
        return this;
    }

    @Step("Add user '{name}' <{email}>")
    public ModalComponent addUser(String name, String email, String job) {
        button("Add user").click();
        ModalComponent modal = new ModalComponent(page, "Add user");
        modal.fill("Name", name).fill("Email", email).fill("Job", job).click("Save");
        return modal;
    }

    /** Deletes via the row action; the confirm() dialog is handled by the session's dialog policy. */
    @Step("Delete user '{name}'")
    public void deleteUser(String name) {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Delete " + name).setExact(true)).click();
    }

    public TableComponent table() {
        return table;
    }

    public Locator emptyState() {
        return page.getByTestId("users-empty");
    }
}
