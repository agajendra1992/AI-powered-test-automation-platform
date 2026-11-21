package io.qaplatform.ai.recommend;

import io.qaplatform.ai.analysis.RuleClassifierView;
import io.qaplatform.ai.llm.AiClient;
import io.qaplatform.ai.llm.LlmException;
import io.qaplatform.ai.llm.PromptManager;
import io.qaplatform.mcp.reporting.ReportReader;
import io.qaplatform.mcp.tools.ToolException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * End-of-run summary: facts from the framework (status, counts, failed scenarios with a rule-based
 * category each) plus an optional model-written paragraph. The paragraph is rejected if it
 * contradicts the facts (e.g. claims everything passed when scenarios failed).
 */
public final class ExecutionSummarizer {

    private static final Pattern ALL_PASSED_CLAIM = Pattern.compile("(?i)\\b(all (tests|scenarios) (passed|succeeded)|no failures|nothing failed|100% pass)");
    private final ReportReader reports;
    private final RuleClassifierView classifier;
    private final AiClient ai;

    public ExecutionSummarizer(ReportReader reports, RuleClassifierView classifier, AiClient ai) {
        this.reports = reports;
        this.classifier = classifier;
        this.ai = ai;
    }

    public Map<String, Object> summarize(String selector, boolean useAi) {
        ReportReader.Source source = reports.resolve(selector);
        Map<String, Object> summary = reports.summary(source);
        List<Map<String, Object>> failed = reports.cucumberScenarios(source).stream()
                .filter(r -> !"passed".equals(r.get("status")) && !"skipped".equals(r.get("status")))
                .map(r -> {
                    Map<String, Object> row = new LinkedHashMap<>(r);
                    row.put("probableCategory", classifier.categoryFor(source, String.valueOf(r.get("scenario"))));
                    return row;
                }).toList();
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("source", source.selector());
        facts.put("status", source.execution() == null ? (number(summary.get("failed")) > 0 ? "FAILED" : "PASSED") : source.execution().status().name());
        for (String k : List.of("suite", "environment", "browser", "total", "passed", "failed", "skipped", "flaky", "retriedAttempts", "durationMs")) {
            facts.put(k, summary.get(k));
        }
        facts.put("flakyScenarios", summary.get("flakyScenarios"));
        facts.put("failedScenarios", failed);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("facts", facts);
        if (useAi && ai.enabled()) {
            try {
                String text = ai.text("execution-summary", Map.of("facts", PromptManager.untrusted("run facts", facts.toString(), 12000))).text();
                if (number(summary.get("failed")) > 0 && ALL_PASSED_CLAIM.matcher(text).find()) {
                    result.put("narrative", Map.of("available", false, "reason", "Discarded: the model's text contradicted the recorded failures"));
                } else {
                    result.put("narrative", Map.of("available", true, "text", text, "note", "AI-written from the facts above; the facts are authoritative"));
                }
            } catch (LlmException e) {
                result.put("narrative", Map.of("available", false, "reason", e.kind() + ": " + e.getMessage()));
            }
        }
        if (summary.isEmpty()) {
            throw ToolException.notFound("No summary for " + selector);
        }
        return result;
    }

    private static long number(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }
}
