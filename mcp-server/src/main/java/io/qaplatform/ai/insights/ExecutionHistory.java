package io.qaplatform.ai.insights;

import io.qaplatform.ai.analysis.RuleClassifier;
import io.qaplatform.mcp.execution.ExecutionRecord;
import io.qaplatform.mcp.execution.ExecutionService;
import io.qaplatform.mcp.reporting.ReportReader;
import io.qaplatform.mcp.tools.ToolException;

import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-scenario outcomes across finished runs (MCP runs and imported CI results), oldest first.
 * The outcome of a scenario in a run is the framework's: passed, failed, skipped, or flaky
 * (failed first, passed on retry, which the framework reports in flakyScenarios).
 */
public final class ExecutionHistory {

    public record Outcome(String executionId, Instant time, String scenario, String status, String environment, String browser,
                          String errorType, String message, String signature) {
    }

    private final ExecutionService executions;
    private final ReportReader reports;

    public ExecutionHistory(ExecutionService executions, ReportReader reports) {
        this.executions = executions;
        this.reports = reports;
    }

    public List<ExecutionRecord> runs(int limit) {
        return executions.list(Integer.MAX_VALUE).stream()
                .filter(r -> r.status().terminal() && Files.isDirectory(executions.resultsDir(r.id())))
                .limit(limit)
                .sorted(Comparator.comparing(ExecutionRecord::createdAt))
                .toList();
    }

    public List<Outcome> outcomes(int lastRuns) {
        List<Outcome> out = new ArrayList<>();
        for (ExecutionRecord run : runs(lastRuns)) {
            out.addAll(outcomesOf(run.id(), run.finishedAt() == null ? run.createdAt() : run.finishedAt(),
                    run.request().environment(), run.request().browser()));
        }
        return out;
    }

    /** Outcomes of one run; also used for the workspace and for run comparisons. */
    public List<Outcome> outcomesOf(String selector, Instant time, String environment, String browser) {
        ReportReader.Source source;
        try {
            source = reports.resolve(selector);
        } catch (ToolException e) {
            return List.of();
        }
        List<Map<String, Object>> failures = reports.failures(source);
        Map<String, Map<String, Object>> lastFailureByName = new LinkedHashMap<>();
        Map<String, String> nameByTestId = new LinkedHashMap<>();
        for (Map<String, Object> f : failures) {
            lastFailureByName.put(String.valueOf(f.get("name")), f);
            nameByTestId.put(String.valueOf(f.get("testId")), String.valueOf(f.get("name")));
        }
        Set<String> flakyNames = new HashSet<>();
        try {
            Object ids = reports.summary(source).get("flakyScenarios");
            if (ids instanceof List<?> list) {
                list.forEach(id -> {
                    String name = nameByTestId.get(String.valueOf(id));
                    if (name != null) {
                        flakyNames.add(name);
                    }
                });
            }
        } catch (ToolException e) {
            // no summary (e.g. imported Cucumber reports only): flaky detection then relies on repeated rows
        }
        Map<String, List<String>> statusesByScenario = new LinkedHashMap<>();
        for (Map<String, Object> row : reports.cucumberScenarios(source)) {
            statusesByScenario.computeIfAbsent(String.valueOf(row.get("scenario")), k -> new ArrayList<>()).add(String.valueOf(row.get("status")));
        }
        List<Outcome> out = new ArrayList<>();
        statusesByScenario.forEach((scenario, statuses) -> {
            String status;
            if (flakyNames.contains(scenario) || (statuses.contains("passed") && statuses.stream().anyMatch(s -> !"passed".equals(s) && !"skipped".equals(s)))) {
                status = "flaky";
            } else if (statuses.stream().allMatch("passed"::equals)) {
                status = "passed";
            } else if (statuses.stream().allMatch("skipped"::equals)) {
                status = "skipped";
            } else {
                status = "failed";
            }
            Map<String, Object> f = lastFailureByName.get(scenario);
            String type = f == null ? null : String.valueOf(f.get("errorType"));
            // Keep the first lines: rules need e.g. both "Expected: ..." and "Received: ..." to classify correctly.
            String message = f == null ? null : firstLines(String.valueOf(f.get("message")), 6);
            out.add(new Outcome(selector, time, scenario, status, environment, browser, type, message,
                    f == null ? null : RuleClassifier.normalizedSignature(type, message)));
        });
        return out;
    }

    private static String firstLines(String s, int n) {
        return s == null ? null : String.join("\n", s.lines().limit(n).toList());
    }
}
