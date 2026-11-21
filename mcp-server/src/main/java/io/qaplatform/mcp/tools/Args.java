package io.qaplatform.mcp.tools;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Strict accessor for tool arguments. Every value is type-checked, length-limited and, where it
 * reaches a command line or a file path, matched against an allow-list pattern. Unknown argument
 * names are rejected so a typo never silently falls back to a default (e.g. a wider test run).
 */
public final class Args {

    /** Free text (scenario names, search queries): printable, no control characters. */
    public static final Pattern TEXT = Pattern.compile("[\\p{L}\\p{N}\\p{P}\\p{Zs}\\p{S}]+");

    private final Map<String, Object> values;

    public Args(Map<String, Object> values, Collection<String> declared) {
        this.values = values == null ? Map.of() : values;
        Set<String> unknown = new TreeSet<>(this.values.keySet());
        unknown.removeAll(declared);
        if (!unknown.isEmpty()) {
            throw ToolException.invalid("Unknown argument(s): " + unknown + ". Allowed: " + new TreeSet<>(declared));
        }
    }

    public Map<String, Object> raw() {
        return values;
    }

    public boolean has(String name) {
        Object v = values.get(name);
        return v != null && !(v instanceof String s && s.isBlank());
    }

    public String string(String name, int maxLength, Pattern pattern) {
        Object v = values.get(name);
        if (v == null) {
            throw ToolException.invalid("Missing required argument '" + name + "'");
        }
        if (!(v instanceof String s)) {
            throw ToolException.invalid("Argument '" + name + "' must be a string");
        }
        String value = s.strip();
        if (value.isEmpty()) {
            throw ToolException.invalid("Argument '" + name + "' must not be empty");
        }
        if (value.length() > maxLength) {
            throw ToolException.invalid("Argument '" + name + "' is longer than " + maxLength + " characters");
        }
        if (pattern != null && !pattern.matcher(value).matches()) {
            throw ToolException.invalid("Argument '" + name + "' has an invalid format");
        }
        return value;
    }

    public String optionalString(String name, int maxLength, Pattern pattern) {
        return has(name) ? string(name, maxLength, pattern) : null;
    }

    public String choice(String name, Collection<String> allowed, String defaultValue) {
        if (!has(name)) {
            if (defaultValue == null) {
                throw ToolException.invalid("Missing required argument '" + name + "'");
            }
            return defaultValue;
        }
        String value = string(name, 64, null).toLowerCase(java.util.Locale.ROOT);
        if (!allowed.contains(value)) {
            throw ToolException.invalid("Argument '" + name + "' must be one of " + new TreeSet<>(allowed) + ", got '" + value + "'");
        }
        return value;
    }

    public int integer(String name, int defaultValue, int min, int max) {
        Object v = values.get(name);
        if (v == null) {
            return defaultValue;
        }
        long n;
        if (v instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())) {
            n = number.longValue();
        } else if (v instanceof String s && s.strip().matches("-?\\d{1,9}")) {
            n = Long.parseLong(s.strip());
        } else {
            throw ToolException.invalid("Argument '" + name + "' must be an integer");
        }
        if (n < min || n > max) {
            throw ToolException.invalid("Argument '" + name + "' must be between " + min + " and " + max);
        }
        return (int) n;
    }

    public boolean bool(String name, boolean defaultValue) {
        Object v = values.get(name);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))) {
            return Boolean.parseBoolean(s);
        }
        throw ToolException.invalid("Argument '" + name + "' must be true or false");
    }
}
