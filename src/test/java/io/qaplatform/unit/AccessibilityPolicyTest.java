package io.qaplatform.unit;

import io.qaplatform.ui.a11y.A11yReport;
import io.qaplatform.ui.a11y.A11yViolation;
import io.qaplatform.ui.a11y.Impact;
import io.qaplatform.ui.visual.VisualResult;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class AccessibilityPolicyTest {

    private static A11yViolation violation(String rule, Impact impact) {
        return new A11yViolation(rule, impact, "help", "https://dequeuniversity.com/rules/axe/" + rule, List.of("#el"));
    }

    @Test
    public void onlyViolationsAtOrAboveTheThresholdBlock() {
        A11yReport report = new A11yReport("Login", "http://x/login", Impact.SERIOUS, List.of(
                violation("region", Impact.MODERATE),
                violation("color-contrast", Impact.SERIOUS),
                violation("button-name", Impact.CRITICAL)), 40);
        assertThat(report.blocking()).extracting(A11yViolation::rule).containsExactly("color-contrast", "button-name");
        assertThat(report.passed()).isFalse();
        assertThat(report.summary()).contains("violations: 3", "blocking (serious or worse): 2", "[moderate] region");
    }

    @Test
    public void lowImpactFindingsAreReportedButDoNotFail() {
        A11yReport report = new A11yReport("Login", "http://x", Impact.SERIOUS, List.of(violation("region", Impact.MINOR)), 10);
        assertThat(report.passed()).isTrue();
        assertThat(report.summary()).contains("[minor] region");
    }

    @Test
    public void unknownImpactIsTreatedAsMinorNotDropped() {
        assertThat(Impact.from(null)).isEqualTo(Impact.MINOR);
        assertThat(Impact.from("weird")).isEqualTo(Impact.MINOR);
        assertThat(Impact.from("Critical")).isEqualTo(Impact.CRITICAL);
    }

    @Test
    public void missingVisualBaselineIsAFailureNotAPass() {
        VisualResult missing = new VisualResult("login", VisualResult.Status.BASELINE_MISSING, 1, 0.001, Path.of("b.png"), Path.of("a.png"));
        assertThat(missing.passed()).isFalse();
        assertThat(missing.message()).contains("No baseline", "visual.update=true");
        assertThat(new VisualResult("login", VisualResult.Status.MATCH, 0, 0.001, Path.of("b"), Path.of("a")).passed()).isTrue();
    }
}
