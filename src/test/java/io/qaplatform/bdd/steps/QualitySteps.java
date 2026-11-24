package io.qaplatform.bdd.steps;

import com.microsoft.playwright.Page;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.qaplatform.bdd.context.Keys;
import io.qaplatform.bdd.context.ScenarioContext;
import io.qaplatform.bdd.support.AppPages;
import io.qaplatform.ui.a11y.A11yReport;
import io.qaplatform.ui.a11y.AccessibilityScanner;
import io.qaplatform.ui.utils.WaitUtils;
import io.qaplatform.ui.visual.VisualCheck;
import io.qaplatform.ui.visual.VisualResult;

import static org.assertj.core.api.Assertions.assertThat;

/** Non-functional UI checks: accessibility (axe-core, WCAG 2.1 AA) and visual regression. */
public class QualitySteps {

    private final ScenarioContext context;

    public QualitySteps(ScenarioContext context) {
        this.context = context;
    }

    // Literal pattern (keep in sync with AppPages): the MCP server reads step patterns from source text.
    @When("^the user opens the (login|dashboard|user management|payment|files) page$")
    public void opensPage(String name) {
        context.put(Keys.CURRENT_PAGE, AppPages.open(context.page(), name));
    }

    @Then("the page should pass the accessibility checks")
    public void passesAccessibilityChecks() {
        Page page = context.page();
        WaitUtils.stableRender(page);
        A11yReport report = AccessibilityScanner.scan(page, page.title());
        assertThat(report.blocking()).as(report.summary()).isEmpty();
    }

    @Then("the page should match the {string} visual baseline")
    public void matchesBaseline(String name) {
        Page page = context.page();
        WaitUtils.stableRender(page);
        VisualResult result = VisualCheck.check(page, name, context.get(Keys.CURRENT_PAGE).dynamicRegions());
        assertThat(result.passed()).as(result.message()).isTrue();
    }
}
