package io.qaplatform.ui.components;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

/** Search input located by its accessible name. */
public class SearchBoxComponent {

    private final Locator input;

    public SearchBoxComponent(Page page, String accessibleName) {
        this.input = page.getByRole(AriaRole.SEARCHBOX, new Page.GetByRoleOptions().setName(accessibleName));
    }

    public void search(String text) {
        input.fill(text);
    }

    public void clear() {
        input.clear();
    }
}
