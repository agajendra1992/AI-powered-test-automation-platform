package io.qaplatform.ui.a11y;

import com.deque.html.axecore.playwright.AxeBuilder;
import com.deque.html.axecore.results.AxeResults;
import com.deque.html.axecore.results.Rule;
import com.microsoft.playwright.Page;
import io.qameta.allure.Allure;
import io.qaplatform.core.config.ConfigManager;
import io.qaplatform.core.reporting.AllureAttachments;
import io.qaplatform.core.reporting.Json;
import io.qaplatform.core.reporting.Redactor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runs Deque axe-core inside the current page and turns the result into an {@link A11yReport}.
 * <p>
 * Configuration (see {@code default.properties}):
 * <ul>
 *   <li>{@code a11y.tags}: axe rule tags to run, default WCAG 2.0/2.1 level A and AA</li>
 *   <li>{@code a11y.fail.impact}: lowest impact that fails the scenario (minor | moderate | serious | critical)</li>
 *   <li>{@code a11y.disabled.rules}: comma-separated axe rule ids to skip; every entry needs a reason in docs/ACCESSIBILITY.md</li>
 * </ul>
 * The scanner only reports; the step definition decides pass or fail.
 */
public final class AccessibilityScanner {

    private AccessibilityScanner() {
    }

    public static A11yReport scan(Page page, String pageName) {
        return Allure.step("Accessibility scan (axe-core): " + pageName, () -> {
            ConfigManager config = ConfigManager.get();
            AxeBuilder axe = new AxeBuilder(page).withTags(list(config.get("a11y.tags", "wcag2a,wcag2aa,wcag21a,wcag21aa")));
            List<String> disabled = list(config.get("a11y.disabled.rules", ""));
            if (!disabled.isEmpty()) {
                axe.disableRules(disabled);
            }
            AxeResults results = axe.analyze();
            List<A11yViolation> violations = new ArrayList<>();
            for (Rule rule : results.getViolations()) {
                List<String> targets = rule.getNodes().stream().map(n -> String.valueOf(n.getTarget())).toList();
                violations.add(new A11yViolation(rule.getId(), Impact.from(rule.getImpact()), rule.getHelp(), rule.getHelpUrl(), targets));
            }
            A11yReport report = new A11yReport(pageName, page.url(), Impact.from(config.get("a11y.fail.impact", "serious")),
                    violations, results.getPasses().size());
            AllureAttachments.text("Accessibility report: " + pageName, report.summary());
            // Node HTML can echo page content: masked like every other attachment.
            AllureAttachments.json("axe-core violations: " + pageName, Redactor.text(Json.write(results.getViolations())));
            return report;
        });
    }

    private static List<String> list(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
