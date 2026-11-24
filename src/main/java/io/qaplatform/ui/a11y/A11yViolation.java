package io.qaplatform.ui.a11y;

import java.util.List;

/** One failed axe rule on one page, with the elements it failed on (CSS selectors). */
public record A11yViolation(String rule, Impact impact, String help, String helpUrl, List<String> targets) {

    public A11yViolation {
        targets = List.copyOf(targets);
    }

    public String describe() {
        StringBuilder sb = new StringBuilder()
                .append('[').append(impact.name().toLowerCase(java.util.Locale.ROOT)).append("] ")
                .append(rule).append(": ").append(help)
                .append(" (").append(targets.size()).append(targets.size() == 1 ? " element) " : " elements) ")
                .append(helpUrl).append('\n');
        targets.forEach(t -> sb.append("    - ").append(t).append('\n'));
        return sb.toString();
    }
}
