package io.qaplatform.ai.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic first-pass classification from well-known failure signatures. Works without AI,
 * explains itself (the matched evidence), and is the baseline the model's opinion is compared with.
 * Rules are checked in order; the most specific signatures come first.
 */
public final class RuleClassifier {

    public record Classification(FailureCategory category, double confidence, String rule, List<String> evidence) {
    }

    private record Rule(String name, FailureCategory category, double confidence, Pattern pattern, Field field) {
    }

    private enum Field { ERROR, ALL, API, DIAGNOSTICS }

    private static Rule rule(String name, FailureCategory c, double conf, Field f, String regex) {
        return new Rule(name, c, conf, Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE), f);
    }

    private static final List<Rule> RULES = List.of(
            rule("browser executable missing", FailureCategory.INFRASTRUCTURE_FAILURE, 0.9, Field.ALL,
                    "Executable doesn't exist|browserType\\.launch|Host system is missing dependencies|OutOfMemoryError|No space left on device"),
            rule("application unreachable", FailureCategory.ENVIRONMENT_FAILURE, 0.85, Field.ALL,
                    "health check failed|ERR_CONNECTION_REFUSED|Connection refused|ECONNREFUSED|503 Service Unavailable|-> 503 \\("),
            rule("DNS / connection reset / TLS", FailureCategory.NETWORK_FAILURE, 0.8, Field.ALL,
                    "ERR_NAME_NOT_RESOLVED|UnknownHostException|ERR_CONNECTION_RESET|Connection reset|ERR_TIMED_OUT|SSLHandshakeException|ERR_CERT_"),
            rule("credentials rejected", FailureCategory.AUTHENTICATION_FAILURE, 0.8, Field.ALL,
                    "Login failed for|-> 401 \\(|-> 403 \\(|HTTP 401|Unauthorized|Invalid (username or )?password|Invalid credentials|token (has )?expired"),
            rule("stayed on sign-in page", FailureCategory.AUTHENTICATION_FAILURE, 0.65, Field.ERROR,
                    "Expected:.*dashboard[\\s\\S]*Received:.*/login"),
            rule("test data missing or conflicting", FailureCategory.TEST_DATA_ISSUE, 0.75, Field.ALL,
                    "Test data not found|Invalid JSON in test data|-> 409 \\(|already exists|duplicate key|Test data setup failed"),
            rule("server error from API", FailureCategory.API_FAILURE, 0.75, Field.API, "-> 5\\d\\d \\("),
            rule("API contract mismatch", FailureCategory.API_FAILURE, 0.7, Field.ERROR,
                    "schema validation|does not match schema|\\$\\.[\\w.\\[\\]]+: .*(required|expected)|Expected status \\d+ but was \\d+|response status"),
            rule("element not found", FailureCategory.LOCATOR_FAILURE, 0.75, Field.ERROR,
                    "strict mode violation|resolved to 0 elements|element\\(s\\) not found|waiting for (getBy\\w+|locator)\\("),
            rule("timeout waiting for state", FailureCategory.TIMING_SYNCHRONIZATION, 0.6, Field.ERROR,
                    "Timeout \\d+ms exceeded|TimeoutError|waiting for (navigation|load state|response)|ConditionTimeout"),
            rule("framework/code error", FailureCategory.AUTOMATION_DEFECT, 0.7, Field.ERROR,
                    "NullPointerException|ClassCastException|IllegalStateException|IndexOutOfBounds|Undefined step|PicoContainer|"
                            + "CucumberException|NoSuchMethodError|Cannot invoke"),
            rule("browser-side errors with a failed check", FailureCategory.APPLICATION_DEFECT, 0.55, Field.DIAGNOSTICS,
                    "Page errors \\([1-9]|Console errors \\([1-9]"),
            rule("assertion on business behaviour", FailureCategory.APPLICATION_DEFECT, 0.45, Field.ERROR,
                    "AssertionFailedError|AssertionError|expected:|Expecting"));

    public Classification classify(FailureEvidence e) {
        for (Rule r : RULES) {
            String text = switch (r.field()) {
                case ERROR -> join(e.errorType(), e.message(), e.stackTop());
                case API -> nz(e.apiCalls());
                case DIAGNOSTICS -> nz(e.browserDiagnostics());
                case ALL -> e.corpus();
            };
            Matcher m = r.pattern().matcher(text);
            if (m.find()) {
                List<String> lines = new ArrayList<>();
                lines.add(lineAround(text, m.start()));
                if (r.category() == FailureCategory.APPLICATION_DEFECT || r.category() == FailureCategory.TIMING_SYNCHRONIZATION) {
                    // Weaker signals: lower confidence for a scenario known to be flaky or one already retried.
                    double c = r.confidence() - (e.flaky() ? 0.15 : 0) - (e.retryAttempt() > 0 ? 0.05 : 0);
                    return new Classification(r.category(), round(c), r.name(), lines);
                }
                return new Classification(r.category(), r.confidence(), r.name(), lines);
            }
        }
        return new Classification(FailureCategory.UNKNOWN, 0.2, "no known signature", List.of());
    }

    private static String lineAround(String text, int index) {
        int start = text.lastIndexOf('\n', index) + 1;
        int end = text.indexOf('\n', index);
        String line = text.substring(start, end < 0 ? text.length() : end).strip();
        return line.length() > 240 ? line.substring(0, 240) + "..." : line;
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            sb.append(nz(p)).append('\n');
        }
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static double round(double v) {
        return Math.max(0.05, Math.round(v * 100) / 100.0);
    }

    public static String normalizedSignature(String errorType, String message) {
        String m = nz(message).toLowerCase(Locale.ROOT)
                .replaceAll("[0-9a-f]{8}-[0-9a-f-]{27,}", "<uuid>")
                .replaceAll("\\d+", "#")
                .replaceAll("\\s+", " ");
        return nz(errorType) + ": " + (m.length() > 160 ? m.substring(0, 160) : m);
    }
}
