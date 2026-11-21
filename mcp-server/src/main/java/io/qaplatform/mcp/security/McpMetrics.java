package io.qaplatform.mcp.security;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** In-process counters exposed in Prometheus text format at GET /metrics (counts and timings only, no content). */
public final class McpMetrics {

    private final Map<String, LongAdder> calls = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> millis = new ConcurrentHashMap<>();
    private final LongAdder authFailures = new LongAdder();

    public void toolCall(String tool, String outcome, long durationMs) {
        calls.computeIfAbsent(tool + "\u0000" + outcome, k -> new LongAdder()).increment();
        millis.computeIfAbsent(tool, k -> new LongAdder()).add(durationMs);
    }

    public void authFailure() {
        authFailures.increment();
    }

    public String render(Map<String, Long> executionsByStatus, long queued) {
        StringBuilder sb = new StringBuilder();
        sb.append("# HELP qa_mcp_tool_calls_total Tool calls by tool and outcome\n# TYPE qa_mcp_tool_calls_total counter\n");
        new TreeMap<>(calls).forEach((k, v) -> {
            String[] parts = k.split("\u0000");
            sb.append("qa_mcp_tool_calls_total{tool=\"").append(parts[0]).append("\",outcome=\"").append(parts[1]).append("\"} ").append(v.sum()).append('\n');
        });
        sb.append("# HELP qa_mcp_tool_duration_ms_total Total time spent in each tool\n# TYPE qa_mcp_tool_duration_ms_total counter\n");
        new TreeMap<>(millis).forEach((k, v) -> sb.append("qa_mcp_tool_duration_ms_total{tool=\"").append(k).append("\"} ").append(v.sum()).append('\n'));
        sb.append("# HELP qa_mcp_auth_failures_total Rejected HTTP requests (missing or invalid token)\n# TYPE qa_mcp_auth_failures_total counter\n")
                .append("qa_mcp_auth_failures_total ").append(authFailures.sum()).append('\n');
        sb.append("# HELP qa_mcp_executions Executions in history by status\n# TYPE qa_mcp_executions gauge\n");
        new TreeMap<>(executionsByStatus).forEach((k, v) -> sb.append("qa_mcp_executions{status=\"").append(k).append("\"} ").append(v).append('\n'));
        sb.append("# HELP qa_mcp_queue_length Runs waiting to start\n# TYPE qa_mcp_queue_length gauge\nqa_mcp_queue_length ").append(queued).append('\n');
        return sb.toString();
    }
}
