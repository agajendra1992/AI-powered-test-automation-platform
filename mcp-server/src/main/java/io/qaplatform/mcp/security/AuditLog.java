package io.qaplatform.mcp.security;

import io.qaplatform.mcp.util.PathNames;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Append-only JSON-lines audit trail: one line per tool call (allowed, denied or failed), per
 * authentication failure and per approval decision. Arguments are redacted and truncated;
 * tokens are never written.
 */
public final class AuditLog {

    private static final int MAX_ARG_CHARS = 500;
    private final Path file;
    private final JsonMapper json = JsonMapper.builder().build();

    public AuditLog(Path file) {
        this.file = file;
        try {
            Files.createDirectories(PathNames.parent(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create audit log directory for " + file, e);
        }
    }

    public Path file() {
        return file;
    }

    public void record(String event, Principal principal, String tool, String outcome, Map<String, ?> details) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("time", Instant.now().toString());
        line.put("event", event);
        line.put("principal", principal == null ? null : principal.name());
        line.put("role", principal == null ? null : principal.role().label());
        line.put("channel", principal == null ? null : principal.channel());
        line.put("tool", tool);
        line.put("outcome", outcome);
        if (details != null) {
            details.forEach((k, v) -> line.put(k, v instanceof String s ? truncate(Redactor.redact(s)) : v));
        }
        write(json.writeValueAsString(line));
    }

    public void toolCall(Principal principal, String tool, String outcome, Map<String, Object> arguments, long durationMs, String detail) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("arguments", truncate(Redactor.redact(json.writeValueAsString(arguments == null ? Map.of() : arguments))));
        details.put("durationMs", durationMs);
        if (detail != null) {
            details.put("detail", detail);
        }
        record("tool_call", principal, tool, outcome, details);
    }

    private synchronized void write(String line) {
        try {
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write audit log " + file, e);
        }
    }

    private static String truncate(String value) {
        return value.length() <= MAX_ARG_CHARS ? value : value.substring(0, MAX_ARG_CHARS) + "...";
    }
}
