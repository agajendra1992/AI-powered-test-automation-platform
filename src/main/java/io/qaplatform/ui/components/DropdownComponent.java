package io.qaplatform.ui.components;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

/** Native select element located by its label. */
public class DropdownComponent {

    private final Locator select;

    public DropdownComponent(Page page, String label) {
        this.select = page.getByLabel(label, new Page.GetByLabelOptions().setExact(true));
    }

    public void choose(String optionLabel) {
        select.selectOption(new com.microsoft.playwright.options.SelectOption().setLabel(optionLabel));
    }

    public Locator root() {
        return select;
    }
}
