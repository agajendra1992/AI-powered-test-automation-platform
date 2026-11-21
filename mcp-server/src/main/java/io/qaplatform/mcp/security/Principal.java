package io.qaplatform.mcp.security;

/**
 * Authenticated caller. {@code name} identifies a person or bot (from the token file, or the OS
 * user for stdio) and is what the audit log and approval rules compare.
 */
public record Principal(String name, Role role, String channel) {

    public static final String CONTEXT_KEY = "qa.mcp.principal";

    public boolean atLeast(Role required) {
        return role.atLeast(required);
    }
}
