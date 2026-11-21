package io.qaplatform.core.assertions;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.LocatorAssertions;
import io.qameta.allure.Allure;
import org.assertj.core.api.SoftAssertions;

import java.util.function.Consumer;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Assertion vocabulary shared by UI, API and BDD layers.
 * Every assertion is a named Allure step, so the report reads like the test intent.
 * UI checks use Playwright's auto-retrying web-first assertions (no manual sleeps).
 */
public final class AssertionUtils {

    private AssertionUtils() {
    }

    public static void visible(Locator locator, String description) {
        Allure.step("Assert visible: " + description, () -> assertThat(locator).isVisible());
    }

    public static void hidden(Locator locator, String description) {
        Allure.step("Assert hidden: " + description, () -> assertThat(locator).isHidden());
    }

    public static void hasText(Locator locator, String expected, String description) {
        Allure.step("Assert '" + description + "' has text '" + expected + "'",
                () -> assertThat(locator).hasText(expected));
    }

    public static void containsText(Locator locator, String expected, String description) {
        Allure.step("Assert '" + description + "' contains '" + expected + "'",
                () -> assertThat(locator).containsText(expected));
    }

    public static void hasValue(Locator locator, String expected, String description) {
        Allure.step("Assert '" + description + "' has value '" + expected + "'",
                () -> assertThat(locator).hasValue(expected));
    }

    public static void urlMatches(Page page, String regex) {
        Allure.step("Assert URL matches " + regex, () -> assertThat(page).hasURL(Pattern.compile(regex)));
    }

    public static void count(Locator locator, int expected, String description) {
        Allure.step("Assert " + description + " count = " + expected,
                () -> assertThat(locator).hasCount(expected, new LocatorAssertions.HasCountOptions()));
    }

    /** Groups several checks; all are evaluated and reported together. */
    public static void softly(String stepName, Consumer<SoftAssertions> checks) {
        Allure.step(stepName, () -> SoftAssertions.assertSoftly(checks));
    }
}
