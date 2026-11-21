package io.qaplatform.ui.components;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

/** Accessible modal dialog located by its accessible name (its heading). */
public class ModalComponent {

    private final Locator dialog;

    public ModalComponent(Page page, String accessibleName) {
        this.dialog = page.getByRole(AriaRole.DIALOG, new Page.GetByRoleOptions().setName(accessibleName));
    }

    public Locator root() {
        return dialog;
    }

    public ModalComponent fill(String label, String value) {
        dialog.getByLabel(label, new Locator.GetByLabelOptions().setExact(true)).fill(value);
        return this;
    }

    public void click(String buttonName) {
        dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName(buttonName).setExact(true)).click();
    }

    public Locator error() {
        return dialog.getByRole(AriaRole.ALERT);
    }
}
