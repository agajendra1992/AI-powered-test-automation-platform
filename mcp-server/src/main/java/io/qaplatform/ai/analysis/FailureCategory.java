package io.qaplatform.ai.analysis;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** The eleven failure classes from the platform specification. */
public enum FailureCategory {
    APPLICATION_DEFECT,
    AUTOMATION_DEFECT,
    LOCATOR_FAILURE,
    TIMING_SYNCHRONIZATION,
    ENVIRONMENT_FAILURE,
    TEST_DATA_ISSUE,
    API_FAILURE,
    NETWORK_FAILURE,
    AUTHENTICATION_FAILURE,
    INFRASTRUCTURE_FAILURE,
    UNKNOWN;

    public static Optional<FailureCategory> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String v = value.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_').replace("_OR_", "_");
        return Arrays.stream(values()).filter(c -> c.name().equals(v) || c.name().startsWith(v)).findFirst();
    }
}
