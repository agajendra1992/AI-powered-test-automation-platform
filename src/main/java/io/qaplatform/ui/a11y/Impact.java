package io.qaplatform.ui.a11y;

import java.util.Locale;

/** axe-core impact levels, least to most severe. */
public enum Impact {
    MINOR, MODERATE, SERIOUS, CRITICAL;

    /** Parses an axe impact string; unknown or missing values count as {@link #MINOR}, so they are reported but never hidden. */
    public static Impact from(String value) {
        if (value == null || value.isBlank()) {
            return MINOR;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return MINOR;
        }
    }

    public boolean atLeast(Impact other) {
        return compareTo(other) >= 0;
    }
}
