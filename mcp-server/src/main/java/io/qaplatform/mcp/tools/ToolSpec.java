package io.qaplatform.mcp.tools;

import io.qaplatform.mcp.security.Principal;
import io.qaplatform.mcp.security.Role;

/**
 * One MCP tool: name, description, minimum role, input schema and handler.
 * {@code readOnly} feeds the MCP tool annotations so clients can tell inspection from execution.
 */
public record ToolSpec(String name, String title, String description, Role requiredRole, boolean readOnly,
                       Schema schema, Handler handler) {

    @FunctionalInterface
    public interface Handler {
        ToolResult handle(Principal principal, Args args);
    }
}
