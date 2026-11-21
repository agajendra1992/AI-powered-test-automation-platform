package io.qaplatform.mcp.tools;

import io.qaplatform.mcp.execution.ExecutionService;
import io.qaplatform.mcp.reporting.ReportReader;
import io.qaplatform.mcp.security.Redactor;
import io.qaplatform.mcp.security.Role;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only access to results of framework runs (MCP runs, or the workspace's target/). */
public final class ReportingTools {

    private static final String EXECUTION_HELP = "Execution id, 'latest' (default: most recent finished run) or 'workspace' (current target/)";
    private static final Set<String> STATUS_FILTERS = Set.of("all", "failed", "passed", "skipped");

    private final ExecutionService executions;
    private final ReportReader reports;
    private final ExecutionTools executionTools;

    public ReportingTools(ExecutionService executions, ReportReader reports, ExecutionTools executionTools) {
        this.executions = executions;
        this.reports = reports;
        this.executionTools = executionTools;
    }

    public List<ToolSpec> tools() {
        return List.of(
                new ToolSpec("get_execution_summary", "Get execution summary",
                        "The framework's execution summary: totals, flaky scenarios, retries, failures by type, API timings, duration.",
                        Role.VIEWER, true, run(Schema.object()),
                        (p, a) -> {
                            ReportReader.Source source = source(a);
                            Map<String, Object> data = new LinkedHashMap<>();
                            data.put("source", source.selector());
                            if (source.execution() != null) {
                                data.put("status", source.execution().status().name());
                            }
                            data.put("summary", reports.summary(source));
                            return ToolResult.of(data);
                        }),
                new ToolSpec("get_failed_scenarios", "Get failed scenarios",
                        "Failed scenarios with error type, message, trimmed stack trace and retry attempt, from the framework's failure "
                                + "records (target/test-failures). Original failures stay listed even when a retry passed.",
                        Role.VIEWER, true, run(Schema.object()),
                        (p, a) -> {
                            ReportReader.Source source = source(a);
                            List<Map<String, Object>> failures = reports.failures(source);
                            return ToolResult.of(Map.of("source", source.selector(), "failures", failures.size(), "records", failures));
                        }),
                new ToolSpec("get_cucumber_report", "Get Cucumber report",
                        "Per-scenario results from the Cucumber JSON report: status, duration, failing step and error.",
                        Role.VIEWER, true,
                        run(Schema.object().choice("status", "Filter by status", STATUS_FILTERS, "all")
                                .integer("limit", "Maximum rows", 200, 1, 500)),
                        (p, a) -> cucumber(a)),
                new ToolSpec("get_allure_report", "Get Allure report information",
                        "Allure results overview (counts by status, attachments) and how to build or open the HTML report.",
                        Role.VIEWER, true, run(Schema.object()),
                        (p, a) -> ToolResult.of(reports.allureOverview(source(a)))),
                new ToolSpec("get_scenario_logs", "Get scenario logs",
                        "Framework log lines written while one scenario ran (thread and scenario are on every line). Secrets are masked.",
                        Role.VIEWER, true,
                        run(Schema.object().string("scenario_name", "Exact scenario name", true, 200)
                                .integer("max_lines", "Maximum lines (latest kept)", 200, 1, 1000)),
                        (p, a) -> {
                            ReportReader.Source source = source(a);
                            String name = a.string("scenario_name", 200, Args.TEXT);
                            List<String> lines = reports.scenarioLog(source, name, a.integer("max_lines", 200, 1, 1000)).stream()
                                    .map(Redactor::redact).toList();
                            return ToolResult.of(Map.of("source", source.selector(), "scenario", name, "lines", lines));
                        }),
                new ToolSpec("get_execution_history", "Get execution history",
                        "Recent runs started through MCP with status, request, requester and counts.",
                        Role.VIEWER, true,
                        Schema.object().integer("limit", "Maximum runs", 20, 1, 100),
                        (p, a) -> ToolResult.of(Map.of("executions", executions.list(a.integer("limit", 20, 1, 100)).stream()
                                .map(r -> executionTools.view(r, false).data()).toList()))),
                new ToolSpec("get_screenshot", "Get failure screenshot",
                        "The screenshot attached to a scenario's result (taken automatically when a UI scenario fails).",
                        Role.VIEWER, true,
                        run(Schema.object().string("scenario_name", "Exact scenario name", true, 200)),
                        (p, a) -> screenshot(a)),
                new ToolSpec("get_trace_information", "Get Playwright trace information",
                        "Playwright trace archives recorded for a scenario (on failure, or every run with trace.mode=on) and the command to "
                                + "open them in the Playwright trace viewer.",
                        Role.VIEWER, true,
                        run(Schema.object().string("scenario_name", "Exact scenario name", true, 200)),
                        (p, a) -> traces(a)));
    }

    private ToolResult cucumber(Args a) {
        ReportReader.Source source = source(a);
        String status = a.choice("status", STATUS_FILTERS, "all");
        int limit = a.integer("limit", 200, 1, 500);
        List<Map<String, Object>> rows = reports.cucumberScenarios(source).stream()
                .filter(r -> "all".equals(status) || status.equals(r.get("status"))
                        || ("failed".equals(status) && !"passed".equals(r.get("status")) && !"skipped".equals(r.get("status"))))
                .toList();
        Map<String, Long> counts = new LinkedHashMap<>();
        reports.cucumberScenarios(source).forEach(r -> counts.merge(String.valueOf(r.get("status")), 1L, Long::sum));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", source.selector());
        data.put("byStatus", counts);
        data.put("scenarios", rows.stream().limit(limit).toList());
        data.put("truncated", rows.size() > limit);
        return ToolResult.of(data);
    }

    private ToolResult screenshot(Args a) {
        ReportReader.Source source = source(a);
        String name = a.string("scenario_name", 200, Args.TEXT);
        JsonNode result = reports.allureResult(source, name)
                .orElseThrow(() -> ToolException.notFound("No result for scenario '" + name + "' in " + source.selector()));
        List<Path> images = reports.attachments(source, result, "image/png");
        if (images.isEmpty()) {
            throw ToolException.notFound("Scenario '" + name + "' has no screenshot (status " + result.path("status").asString("?")
                    + "; screenshots are taken for failed UI scenarios)");
        }
        Path last = images.get(images.size() - 1);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", source.selector());
        data.put("scenario", name);
        data.put("status", result.path("status").asString("?"));
        data.put("screenshots", images.size());
        data.put("returned", "last screenshot of the latest attempt");
        return ToolResult.withImage(data, Base64.getEncoder().encodeToString(reports.image(last)));
    }

    private ToolResult traces(Args a) {
        ReportReader.Source source = source(a);
        String name = a.string("scenario_name", 200, Args.TEXT);
        JsonNode result = reports.allureResult(source, name)
                .orElseThrow(() -> ToolException.notFound("No result for scenario '" + name + "' in " + source.selector()));
        List<Map<String, Object>> traces = reports.attachments(source, result, "application/zip").stream().map(reports::traceInfo).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", source.selector());
        data.put("scenario", name);
        data.put("status", result.path("status").asString("?"));
        data.put("traces", traces);
        if (traces.isEmpty()) {
            data.put("note", "No trace recorded. Traces are kept on failure (trace.mode=retain-on-failure) or always with trace.mode=on.");
        }
        return ToolResult.of(data);
    }

    private ReportReader.Source source(Args a) {
        return reports.resolve(a.optionalString("execution_id", 40, java.util.regex.Pattern.compile("latest|workspace|run-[0-9a-f-]+")));
    }

    private static Schema run(Schema schema) {
        return schema.string("execution_id", EXECUTION_HELP, false, 40);
    }
}
