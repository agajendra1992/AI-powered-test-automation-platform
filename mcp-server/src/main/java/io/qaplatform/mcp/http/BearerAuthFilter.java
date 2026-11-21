package io.qaplatform.mcp.http;

import io.qaplatform.mcp.security.AuditLog;
import io.qaplatform.mcp.security.Principal;
import io.qaplatform.mcp.security.TokenStore;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Rejects every MCP HTTP request without a valid {@code Authorization: Bearer <token>} header
 * (401) before it reaches the MCP transport, and attaches the authenticated principal.
 */
public final class BearerAuthFilter implements Filter {

    public static final String ATTRIBUTE = Principal.CONTEXT_KEY;
    private final TokenStore tokens;
    private final AuditLog audit;
    private final io.qaplatform.mcp.security.McpMetrics metrics;

    public BearerAuthFilter(TokenStore tokens, AuditLog audit, io.qaplatform.mcp.security.McpMetrics metrics) {
        this.tokens = tokens;
        this.audit = audit;
        this.metrics = metrics;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String header = request.getHeader("Authorization");
        Optional<Principal> principal = Optional.empty();
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            principal = tokens.authenticate(header.substring(7).trim());
        }
        if (principal.isEmpty()) {
            metrics.authFailure();
            audit.record("auth_failure", null, null, header == null ? "missing_token" : "invalid_token",
                    Map.of("remote", String.valueOf(request.getRemoteAddr()), "method", request.getMethod()));
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Bearer realm=\"qa-platform-mcp\"");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"A valid bearer token is required\"}");
            return;
        }
        request.setAttribute(ATTRIBUTE, principal.get());
        chain.doFilter(req, res);
    }
}
