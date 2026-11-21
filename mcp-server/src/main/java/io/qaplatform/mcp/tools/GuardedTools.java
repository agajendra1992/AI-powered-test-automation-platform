package io.qaplatform.mcp.tools;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.qaplatform.mcp.security.AuditLog;
import io.qaplatform.mcp.security.Principal;
import io.qaplatform.mcp.security.Redactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns {@link ToolSpec}s into MCP tool registrations and puts every call through the same gate:
 * identify the caller, check the tool's role, validate arguments, run, redact, audit.
 * Handlers never see an unauthenticated or unauthorised call.
 */
public final class GuardedTools {

    private static final Logger LOG = LoggerFactory.getLogger(GuardedTools.class);
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private final Principal stdioPrincipal;
    private final AuditLog audit;
    private final io.qaplatform.mcp.security.McpMetrics metrics;

    /**
     * @param stdioPrincipal identity used when the transport carries none (stdio: the local OS user
     *                       with the configured role). HTTP calls always carry an authenticated principal.
     */
    public GuardedTools(Principal stdioPrincipal, AuditLog audit) {
        this(stdioPrincipal, audit, new io.qaplatform.mcp.security.McpMetrics());
    }

    public GuardedTools(Principal stdioPrincipal, AuditLog audit, io.qaplatform.mcp.security.McpMetrics metrics) {
        this.stdioPrincipal = stdioPrincipal;
        this.audit = audit;
        this.metrics = metrics;
    }

    public List<SyncToolSpecification> register(List<ToolSpec> specs) {
        List<SyncToolSpecification> result = new ArrayList<>();
        for (ToolSpec spec : specs) {
            McpSchema.Tool tool = McpSchema.Tool.builder()
                    .name(spec.name())
                    .title(spec.title())
                    .description(spec.description() + " [requires role: " + spec.requiredRole().label() + "]")
                    .inputSchema(spec.schema().toMap())
                    .annotations(new McpSchema.ToolAnnotations(spec.title(), spec.readOnly(), false, spec.readOnly(), false, null))
                    .build();
            result.add(SyncToolSpecification.builder()
                    .tool(tool)
                    .callHandler((exchange, request) -> call(spec, exchange, request))
                    .build());
        }
        return result;
    }

    McpSchema.CallToolResult call(ToolSpec spec, McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        long start = System.nanoTime();
        Principal principal = principal(exchange);
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        try {
            if (principal == null) {
                throw ToolException.forbidden("Not authenticated");
            }
            if (!principal.atLeast(spec.requiredRole())) {
                throw ToolException.forbidden("Tool '" + spec.name() + "' requires role '" + spec.requiredRole().label()
                        + "'; you have '" + principal.role().label() + "'");
            }
            Args args = new Args(arguments, spec.schema().names());
            ToolResult result = spec.handler().handle(principal, args);
            audit.toolCall(principal, spec.name(), "ok", arguments, elapsed(start), auditDetail(result));
            metrics.toolCall(spec.name(), "ok", elapsed(start));
            return success(result);
        } catch (ToolException e) {
            audit.toolCall(principal, spec.name(), e.code(), arguments, elapsed(start), e.getMessage());
            metrics.toolCall(spec.name(), e.code(), elapsed(start));
            return error(e.code(), e.getMessage(), null);
        } catch (RuntimeException e) {
            String incident = UUID.randomUUID().toString().substring(0, 8);
            LOG.error("Tool {} failed (incident {})", spec.name(), incident, e);
            audit.toolCall(principal, spec.name(), "internal_error", arguments, elapsed(start), "incident " + incident);
            metrics.toolCall(spec.name(), "internal_error", elapsed(start));
            return error("internal_error", "The tool failed unexpectedly. Server log incident id: " + incident, incident);
        }
    }

    private Principal principal(McpSyncServerExchange exchange) {
        McpTransportContext context = exchange == null ? null : exchange.transportContext();
        Object fromTransport = context == null ? null : context.get(Principal.CONTEXT_KEY);
        if (fromTransport instanceof Principal p) {
            return p;
        }
        return stdioPrincipal;
    }

    private static String auditDetail(ToolResult result) {
        Object id = result.data().get("executionId");
        Object status = result.data().get("status");
        return id == null ? null : "execution " + id + (status == null ? "" : " " + status);
    }

    /** Redacts every string value (numbers and keys untouched, so the JSON stays valid). */
    static Object redactTree(Object value) {
        if (value instanceof String s) {
            return Redactor.redact(s);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), redactTree(v)));
            return copy;
        }
        if (value instanceof Iterable<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(v -> copy.add(redactTree(v)));
            return copy;
        }
        return value;
    }

    private static McpSchema.CallToolResult success(ToolResult result) {
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) redactTree(JSON.convertValue(result.data(), LinkedHashMap.class));
        String text = JSON.writeValueAsString(structured);
        McpSchema.CallToolResult.Builder builder = McpSchema.CallToolResult.builder()
                .addTextContent(text)
                .structuredContent(structured)
                .isError(false);
        for (ToolResult.Image image : result.images()) {
            builder.addContent(new McpSchema.ImageContent(null, image.base64(), image.mimeType()));
        }
        return builder.build();
    }

    private static McpSchema.CallToolResult error(String code, String message, String incident) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", Redactor.redact(message));
        if (incident != null) {
            body.put("incident", incident);
        }
        return McpSchema.CallToolResult.builder()
                .addTextContent(JSON.writeValueAsString(body))
                .structuredContent(body)
                .isError(true)
                .build();
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
