package io.qaplatform.mcp.security;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks secrets in any text the server returns or logs (console output, stack traces, report
 * snippets, audit arguments). Mirrors the framework's own Redactor so MCP never re-exposes
 * what the framework already hides.
 */
public final class Redactor {

    private static final String MASK = "****";

    private record Rule(Pattern pattern, String replacement) {
    }

    private static final List<Rule> RULES = List.of(
            // Authorization headers and bearer/basic credentials
            new Rule(Pattern.compile("(?i)(authorization\\s*[:=]\\s*\"?)(bearer|basic)\\s+[A-Za-z0-9._~+/=-]+"), "$1$2 " + MASK),
            new Rule(Pattern.compile("(?i)\\b(bearer)\\s+[A-Za-z0-9._~+/=-]{8,}"), "$1 " + MASK),
            // MCP tokens
            new Rule(Pattern.compile("qamcp_[A-Za-z0-9_-]{20,}"), "qamcp_" + MASK),
            // key=value and "key": "value" for sensitive keys
            new Rule(Pattern.compile("(?i)(\"(?:password|passwd|pwd|secret|client_secret|clientSecret|token|access_token|"
                    + "accessToken|refresh_token|api[_-]?key|cvv|cardNumber|card_number)\"\\s*:\\s*\")[^\"]*(\")"), "$1" + MASK + "$2"),
            new Rule(Pattern.compile("(?i)\\b((?:password|passwd|pwd|secret|client_secret|token|access_token|api[_-]?key|cvv)"
                    + "\\s*[=:]\\s*)[^\\s,;&\"']+"), "$1" + MASK));

    /**
     * Card-number candidates: 13-19 contiguous digits, or groups of four separated by one space or
     * dash. Only Luhn-valid candidates are masked, so ids and timestamps (run-20261119-170803-...)
     * stay readable.
     */
    private static final Pattern CARD = Pattern.compile("(?<![\\d-])(?:\\d{13,19}|\\d{4}([ -])\\d{4}\\1\\d{4}\\1\\d{1,7})(?![\\d-])");

    private Redactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(result);
            result = m.replaceAll(rule.replacement());
        }
        return CARD.matcher(result).replaceAll(match -> {
            String digits = match.group().replaceAll("[ -]", "");
            return digits.length() >= 13 && digits.length() <= 19 && luhn(digits) ? MASK + "card" + MASK : Matcher.quoteReplacement(match.group());
        });
    }

    static boolean luhn(String digits) {
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }
}
