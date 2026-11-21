package io.qaplatform.mcp.security;

import java.util.Locale;

/**
 * Tool permission levels, lowest first. A principal may call every tool at or below its role.
 * <ul>
 *   <li>VIEWER: discovery, reports, history, browser inspection (read-only).</li>
 *   <li>RUNNER: also starts and cancels its own test runs.</li>
 *   <li>ADMIN: also approves or rejects runs that need approval, and cancels anyone's run.</li>
 * </ul>
 */
public enum Role {
    VIEWER, RUNNER, ADMIN;

    public boolean atLeast(Role required) {
        return compareTo(required) >= 0;
    }

    public static Role parse(String value) {
        try {
            return Role.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown role '" + value + "' (viewer, runner, admin)", e);
        }
    }

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
