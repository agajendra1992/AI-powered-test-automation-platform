package io.qaplatform.core.reporting;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes secrets and sensitive data before anything reaches logs or reports.
 * Applied to every API request/response attachment.
 */
public final class Redactor {

    private static final String MASK = "****";
    private static final Set<String> SECRET_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key");
    /** JSON fields whose values are always fully masked. */
    private static final Pattern SECRET_FIELDS = Pattern.compile(
            "(\"(?:password|token|access_token|refresh_token|client_secret|secret|api_key|cvv)\"\\s*:\\s*)\"[^\"]*\"",
            Pattern.CASE_INSENSITIVE);
    /** Card numbers keep the last four digits (PCI-style masking). */
    private static final Pattern CARD_FIELD = Pattern.compile(
            "(\"(?:cardNumber|card_number)\"\\s*:\\s*)\"\\d*(\\d{4})\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern BEARER = Pattern.compile("(Bearer\\s+)[A-Za-z0-9._~+/=-]+");

    private Redactor() {
    }

    public static String text(String input) {
        if (input == null) {
            return null;
        }
        String out = SECRET_FIELDS.matcher(input).replaceAll("$1\"" + MASK + "\"");
        Matcher card = CARD_FIELD.matcher(out);
        out = card.replaceAll("$1\"" + MASK + "$2\"");
        return BEARER.matcher(out).replaceAll("$1" + MASK);
    }

    public static Map<String, String> headers(Map<String, String> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        headers.forEach((k, v) -> out.put(k, SECRET_HEADERS.contains(k.toLowerCase(Locale.ROOT)) ? MASK : v));
        return out;
    }
}
