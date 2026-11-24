package io.qaplatform.ui.a11y;

import java.util.List;

/**
 * Result of one accessibility scan. Every violation is reported; only those at or above
 * {@code failOn} are {@link #blocking()}, so lower-impact findings stay visible without failing the build.
 */
public record A11yReport(String pageName, String url, Impact failOn, List<A11yViolation> violations, int passedRules) {

    public A11yReport {
        violations = List.copyOf(violations);
    }

    public List<A11yViolation> blocking() {
        return violations.stream().filter(v -> v.impact().atLeast(failOn)).toList();
    }

    public boolean passed() {
        return blocking().isEmpty();
    }

    public String summary() {
        StringBuilder sb = new StringBuilder()
                .append("Accessibility scan: ").append(pageName).append(" (").append(url).append(")\n")
                .append("Rules passed: ").append(passedRules)
                .append(", violations: ").append(violations.size())
                .append(", blocking (").append(failOn.name().toLowerCase(java.util.Locale.ROOT)).append(" or worse): ")
                .append(blocking().size()).append('\n');
        violations.forEach(v -> sb.append(v.describe()));
        return sb.toString();
    }
}
