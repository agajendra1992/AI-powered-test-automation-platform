package io.qaplatform.ai.insights;

import io.qaplatform.mcp.config.McpConfig;
import io.qaplatform.mcp.execution.ExecutionRecord;
import io.qaplatform.mcp.execution.ExecutionService;
import io.qaplatform.mcp.security.Principal;
import io.qaplatform.mcp.security.Role;
import io.qaplatform.mcp.tools.Args;
import io.qaplatform.mcp.tools.Schema;
import io.qaplatform.mcp.tools.ToolException;
import io.qaplatform.mcp.tools.ToolResult;
import io.qaplatform.mcp.tools.ToolSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Intelligent-QA tools over run history: flakiness, failure correlation, selection effectiveness, maintenance. */
public final class InsightsTools {

    private static final Pattern RUN_ID = Pattern.compile("run-\\d{8}-\\d{6}-[0-9a-f]{6}");
    private static final Pattern RELATIVE_DIR = Pattern.compile("[A-Za-z0-9_./-]{1,300}");

    private final McpConfig config;
    private final ExecutionService executions;
    private final ExecutionHistory history;
    private final MaintenanceAdvisor maintenance;

    public InsightsTools(McpConfig config, ExecutionService executions, ExecutionHistory history, MaintenanceAdvisor maintenance) {
        this.config = config;
        this.executions = executions;
        this.history = history;
        this.maintenance = maintenance;
    }

    public List<ToolSpec> tools() {
        return List.of(
                new ToolSpec("get_flaky_tests", "Flaky test analytics",
                        "Per-scenario history across recent runs (MCP and imported CI runs): passes, failures, passed-after-retry, status "
                                + "flips, distinct errors, environments/browsers, and FLAKY / CONSISTENTLY_FAILING / RECOVERED / STABLE.",
                        Role.VIEWER, true,
                        Schema.object().integer("last_runs", "How many recent runs to analyse", 30, 2, 500)
                                .integer("min_runs", "Ignore scenarios executed fewer times", 2, 1, 50)
                                .bool("include_stable", "Also list stable scenarios", false),
                        (p, a) -> flaky(a)),
                new ToolSpec("correlate_failures", "Correlate failures across runs",
                        "Groups failures from recent runs by normalised error signature: occurrences, runs, scenarios, environments, "
                                + "probable category, and hints (one shared cause hitting many scenarios, recurring known issues).",
                        Role.VIEWER, true, Schema.object().integer("last_runs", "How many recent runs to include", 30, 1, 500),
                        (p, a) -> {
                            int runs = a.integer("last_runs", 30, 1, 500);
                            return ToolResult.of(Map.of("runsAnalysed", history.runs(runs).size(),
                                    "clusters", new FailureCorrelator().clusters(history.outcomes(runs))));
                        }),
                new ToolSpec("compare_with_full_regression", "Compare a targeted run with full regression",
                        "Checks a targeted run against a full-regression run: failures the selection caught, failures only the full "
                                + "run found (escaped), and scenarios whose result differed.",
                        Role.VIEWER, true,
                        Schema.object().string("selected_execution_id", "The targeted run", true, 40)
                                .string("full_execution_id", "A full regression run", true, 40),
                        (p, a) -> compare(a)),
                new ToolSpec("import_execution_results", "Import CI results into history",
                        "Registers framework results produced elsewhere (e.g. CI artifacts unpacked inside the project: a directory with "
                                + "execution-summary.json / cucumber-reports / test-failures / allure-results) as a finished run.",
                        Role.RUNNER, false,
                        Schema.object().string("results_dir", "Directory inside the project, e.g. ci-artifacts/run-412/target", true, 300)
                                .string("label", "Label, e.g. 'CI #412 shard 2'", false, 80),
                        this::importResults),
                new ToolSpec("suggest_test_maintenance", "Suggest test maintenance",
                        "Findings with evidence: unused step definitions, duplicate scenarios, long scenarios, single-row outlines, "
                                + "quarantined scenarios, brittle locators, flaky and consistently failing scenarios. With AI (runner role), "
                                + "a prioritised plan that references the findings. Nothing is changed.",
                        Role.VIEWER, true, Schema.object().bool("use_ai", "Add an AI-prioritised plan (runner role)", true),
                        (p, a) -> ToolResult.of(maintenance.advise(a.bool("use_ai", true) && p.atLeast(Role.RUNNER)))));
    }

    private ToolResult flaky(Args a) {
        int runs = a.integer("last_runs", 30, 2, 500);
        List<Map<String, Object>> rows = new FlakyAnalyzer().analyze(history.outcomes(runs), a.integer("min_runs", 2, 1, 50));
        boolean includeStable = a.bool("include_stable", false);
        Map<String, Long> counts = new LinkedHashMap<>();
        rows.forEach(r -> counts.merge(String.valueOf(r.get("classification")), 1L, Long::sum));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runsAnalysed", history.runs(runs).size());
        m.put("counts", counts);
        m.put("scenarios", includeStable ? rows : rows.stream().filter(r -> !"STABLE".equals(r.get("classification"))).toList());
        m.put("legend", "history: p=passed f=failed F=flaky(passed on retry) s=skipped, oldest first");
        m.put("note", "Report only. Quarantining or fixing is a team decision; quarantined scenarios stop running entirely.");
        return ToolResult.of(m);
    }

    private ToolResult compare(Args a) {
        String selected = a.string("selected_execution_id", 40, RUN_ID);
        String full = a.string("full_execution_id", 40, RUN_ID);
        ExecutionRecord fullRun = executions.get(full).orElseThrow(() -> ToolException.notFound("No execution " + full));
        ExecutionRecord selectedRun = executions.get(selected).orElseThrow(() -> ToolException.notFound("No execution " + selected));
        Map<String, Object> result = new LinkedHashMap<>(new RunComparator().compare(selected,
                history.outcomesOf(selected, selectedRun.finishedAt(), selectedRun.request().environment(), selectedRun.request().browser()),
                full, history.outcomesOf(full, fullRun.finishedAt(), fullRun.request().environment(), fullRun.request().browser())));
        if (fullRun.request().tagExpression() != null || fullRun.request().scenarioNames() != null
                || !"testng-regression.xml".equals(fullRun.request().suite())) {
            result.put("warning", full + " is not a plain full-regression run (" + fullRun.request().kind() + "); escapes may be under-reported");
        }
        return ToolResult.of(result);
    }

    private ToolResult importResults(Principal p, Args a) {
        String dir = a.string("results_dir", 300, RELATIVE_DIR);
        if (dir.contains("..")) {
            throw ToolException.invalid("results_dir must not contain '..'");
        }
        ExecutionRecord r = executions.importResults(config.projectDir().resolve(dir), a.has("label") ? a.string("label", 80, Args.TEXT) : "import", p);
        return ToolResult.of(Map.of("executionId", r.id(), "status", r.status().name(), "message", r.message(),
                "summary", r.summary() == null ? Map.of() : r.summary()));
    }
}
