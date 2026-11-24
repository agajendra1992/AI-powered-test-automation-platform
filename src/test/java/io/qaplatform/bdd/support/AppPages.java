package io.qaplatform.bdd.support;

import com.microsoft.playwright.Page;
import io.qaplatform.ui.pages.BasePage;
import io.qaplatform.ui.pages.DashboardPage;
import io.qaplatform.ui.pages.FilesPage;
import io.qaplatform.ui.pages.LoginPage;
import io.qaplatform.ui.pages.PaymentPage;
import io.qaplatform.ui.pages.UserManagementPage;

/** Maps the page names used in feature files to page objects. */
public final class AppPages {

    private AppPages() {
    }

    public static BasePage open(Page page, String name) {
        return switch (name) {
            case "login" -> new LoginPage(page).open();
            case "dashboard" -> new DashboardPage(page).open();
            case "user management" -> new UserManagementPage(page).open();
            case "payment" -> new PaymentPage(page).open();
            case "files" -> new FilesPage(page).open();
            default -> throw new IllegalArgumentException("Unknown page: " + name);
        };
    }
}
