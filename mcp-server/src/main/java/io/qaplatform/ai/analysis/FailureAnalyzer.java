package io.qaplatform.ai.analysis;

import io.qaplatform.ai.llm.AiClient;
import io.qaplatform.ai.llm.LlmException;
import io.qaplatform.ai.llm.PromptManager;
import io.qaplatform.mcp.execution.ExecutionRecord;
import io.qaplatform.mcp.execution.ExecutionService;
import io.qaplatform.mcp.reporting.ReportReader;
import io.qaplatform.mcp.tools.ToolException;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Failure analysis = deterministic evidence + rule-based classification + (optional) model
 * opinion. The test result is never changed: the output repeats the framework's verdict and labels
 * every AI statement as an unconfirmed hypothesis. Evidence the model cites is checked against
 * the real evidence text; unverifiable citations lower its confidence.
 */
public final class FailureAnalyzer {

    private final ReportReader reports;
    private final ExecutionService executions;
    private final AiClient ai;
    private final io.qaplatform.ai.rag.RagClient rag;
    private final RuleClassifier rules = new RuleClassifier();

    public FailureAnalyzer(ReportReader reports, ExecutionService executions, AiClient ai) {
        this(reports, executions, ai, new io.qaplatform.ai.rag.RagClient(null, null));
    }

    public FailureAnalyzer(ReportReader reports, ExecutionService executions, AiClient ai, io.qaplatform.ai.rag.RagClient rag) {
        this.reports = reports;
        this.executions = executions;
        this.ai = ai;
        this.rag = rag;
    }

    public Map<String, Object> analyze(String selector, String scenario, boolean useAi) {
        ReportReader.Source source = reports.resolve(selector);
        FailureEvidence evidence = collect(source, scenario);
        RuleClassifier.Classification baseline = rules.classify(evidence);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", scenario);
        result.put("source", source.selector());
        result.put("testResult", "FAILED (recorded by the framework; this analysis does not change it)");
        result.put("evidence", evidenceSummary(evidence));
        Map<String, Object> ruleView = new LinkedHashMap<>();
        ruleView.put("category", baseline.category().name());
        ruleView.put("confidence", baseline.confidence());
        ruleView.put("rule", baseline.rule());
        ruleView.put("matchedEvidence", baseline.evidence());
        result.put("ruleBasedClassification", ruleView);
        result.put("similarFailures", evidence.history());
        List<Map<String, Object>> knowledge = knowledge(evidence);
        if (knowledge != null) {
            result.put("knowledgeBaseMatches", knowledge);
        }

        if (!useAi) {
            result.put("aiAssessment", Map.of("available", false, "reason", "Not requested (use_ai=false)"));
        } else if (!ai.enabled()) {
            result.put("aiAssessment", Map.of("available", false, "reason", "AI provider not configured (QA_AI_PROVIDER)"));
        } else {
            result.put("aiAssessment", aiAssessment(evidence, baseline, knowledge));
        }
        result.put("disclaimer", "Categories and root causes are hypotheses to investigate, not confirmed causes. "
                + "Confirm with the screenshot (get_screenshot), trace (get_trace_information) and logs (get_scenario_logs).");
        return result;
    }

    // ------------------------------------------------------------------ evidence

    FailureEvidence collect(ReportReader.Source source, String scenario) {
        List<Map<String, Object>> records = reports.failures(source).stream()
                .filter(r -> scenario.equals(r.get("name")))
                .sorted(Comparator.comparing(r -> String.valueOf(r.get("timestamp"))))
                .toList();
        Map<String, Object> cucumber = reports.cucumberScenarios(source).stream()
                .filter(r -> scenario.equals(r.get("scenario")) && !"passed".equals(r.get("status")))
                .findFirst().orElse(null);
        if (records.isEmpty() && cucumber == null) {
            throw ToolException.notFound("Scenario '" + scenario + "' has no failure in " + source.selector()
                    + ". Use get_failed_scenarios to list failed scenarios.");
        }
        Map<String, Object> record = records.isEmpty() ? Map.of() : records.get(records.size() - 1);
        Map<String, String> attachments = reports.allureResult(source, scenario)
                .map(r -> reports.textAttachments(source, r, 6000)).orElse(Map.of());
        List<String> log;
        try {
            log = reports.scenarioLog(source, scenario, 30);
        } catch (ToolException e) {
            log = List.of();
        }
        boolean flaky = false;
        try {
            Object flakyList = reports.summary(source).get("flakyScenarios");
            flaky = flakyList instanceof List<?> l && l.stream().anyMatch(id -> Objects.equals(id, record.get("testId")));
        } catch (ToolException e) {
            flaky = false;
        }
        String message = str(record.get("message"), cucumber == null ? null : str(cucumber.get("error"), null));
        String errorType = str(record.get("errorType"), null);
        return new FailureEvidence(source.selector(), scenario, str(record.get("environment"), null), str(record.get("browser"), null),
                errorType, message, firstLines(str(record.get("stackTrace"), ""), 15),
                cucumber == null ? null : str(cucumber.get("failedStep"), null),
                record.get("retryAttempt") instanceof Number n ? n.intValue() : 0, flaky,
                attachments.getOrDefault("Browser diagnostics", null), attachments.getOrDefault("API calls in this scenario", null),
                log, history(source, scenario, RuleClassifier.normalizedSignature(errorType, message)));
    }

    private List<Map<String, Object>> history(ReportReader.Source current, String scenario, String signature) {
        List<Map<String, Object>> similar = new ArrayList<>();
        for (ExecutionRecord run : executions.list(50)) {
            if (run.id().equals(current.selector()) || !run.status().terminal()) {
                continue;
            }
            ReportReader.Source source;
            try {
                source = reports.resolve(run.id());
            } catch (ToolException e) {
                continue;
            }
            for (Map<String, Object> f : reports.failures(source)) {
                boolean sameScenario = scenario.equals(f.get("name"));
                boolean sameSignature = signature.equals(RuleClassifier.normalizedSignature(str(f.get("errorType"), null), str(f.get("message"), null)));
                if (sameScenario || sameSignature) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("executionId", run.id());
                    row.put("finishedAt", String.valueOf(run.finishedAt()));
                    row.put("scenario", f.get("name"));
                    row.put("match", sameScenario && sameSignature ? "same scenario, same error" : sameScenario ? "same scenario" : "same error");
                    row.put("message", firstLines(str(f.get("message"), ""), 2));
                    similar.add(row);
                }
            }
            if (similar.size() >= 10) {
                break;
            }
        }
        return similar;
    }

    // ------------------------------------------------------------------ AI

    /** Past failures, bug reports and troubleshooting notes from the knowledge base (null when RAG is not configured). */
    private List<Map<String, Object>> knowledge(FailureEvidence e) {
        if (!rag.configured()) {
            return null;
        }
        try {
            String query = e.scenario() + " " + (e.errorType() == null ? "" : e.errorType()) + " " + firstLines(e.message() == null ? "" : e.message(), 2);
            JsonNode hits = rag.search(query.length() > 900 ? query.substring(0, 900) : query, 5,
                    Map.of("document_type", List.of("failure", "cucumber_report", "troubleshooting", "bug_report", "documentation")), "hybrid");
            List<Map<String, Object>> out = new ArrayList<>();
            for (JsonNode h : hits.path("results")) {
                String text = h.path("text").asString("");
                out.add(Map.of("citation", h.path("citation").asString(""), "type", h.path("metadata").path("document_type").asString(""),
                        "excerpt", text.length() > 500 ? text.substring(0, 500) + " ..." : text));
            }
            return out;
        } catch (ToolException ex) {
            return List.of(Map.of("unavailable", ex.getMessage()));
        }
    }

    private Map<String, Object> aiAssessment(FailureEvidence e, RuleClassifier.Classification baseline, List<Map<String, Object>> knowledge) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("categories", String.join(", ", java.util.Arrays.stream(FailureCategory.values()).map(Enum::name).toList()));
        values.put("baseline", baseline.category() + " (rule: " + baseline.rule() + ", confidence " + baseline.confidence() + ")");
        values.put("scenario", PromptManager.untrusted("scenario name", e.scenario(), 300));
        values.put("failure", PromptManager.untrusted("failure record", "Error type: " + e.errorType() + "\nMessage: " + e.message()
                + "\nFailed step: " + e.failedStep() + "\nRetry attempt: " + e.retryAttempt() + "\nKnown flaky: " + e.flaky()
                + "\nEnvironment: " + e.environment() + ", browser: " + e.browser(), 4000));
        values.put("stack", PromptManager.untrusted("stack trace (top)", e.stackTop(), 3000));
        values.put("diagnostics", PromptManager.untrusted("browser diagnostics", e.browserDiagnostics(), 3000));
        values.put("api", PromptManager.untrusted("API calls", e.apiCalls(), 3000));
        values.put("log", PromptManager.untrusted("framework log", String.join("\n", e.logTail()), 3000));
        values.put("history", PromptManager.untrusted("similar earlier failures", e.history().toString(), 2000));
        values.put("knowledge", PromptManager.untrusted("knowledge base matches (past failures, bug reports, notes)",
                knowledge == null ? "(knowledge base not configured)" : knowledge.toString(), 3000));
        try {
            AiClient.Answer answer = ai.json("failure-analysis", values);
            return validate(answer, e, baseline);
        } catch (LlmException ex) {
            return Map.of("available", false, "reason", ex.kind() + ": " + ex.getMessage());
        }
    }

    private static Map<String, Object> validate(AiClient.Answer answer, FailureEvidence e, RuleClassifier.Classification baseline) {
        JsonNode j = answer.json();
        FailureCategory category = FailureCategory.parse(j.path("category").asString(null)).orElse(FailureCategory.UNKNOWN);
        double confidence = Math.max(0, Math.min(1, j.path("confidence").asDouble(0.3)));
        String corpus = normalize(e.corpus());
        List<String> verified = new ArrayList<>();
        List<String> unverified = new ArrayList<>();
        for (JsonNode quote : j.path("supporting_evidence")) {
            String q = quote.asString("").strip();
            if (q.length() < 4) {
                continue;
            }
            (corpus.contains(normalize(q)) ? verified : unverified).add(q);
        }
        List<String> warnings = new ArrayList<>();
        if (verified.isEmpty()) {
            confidence = Math.min(confidence, 0.3);
            warnings.add("None of the cited evidence was found verbatim in the recorded evidence; confidence capped at 0.3.");
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("available", true);
        view.put("status", "UNCONFIRMED HYPOTHESIS");
        view.put("category", category.name());
        view.put("probableRootCause", j.path("probable_root_cause").asString(""));
        view.put("confidence", Math.round(confidence * 100) / 100.0);
        view.put("supportingEvidenceVerified", verified);
        view.put("supportingEvidenceNotFound", unverified);
        view.put("recommendedAction", j.path("recommended_action").asString(""));
        view.put("agreesWithRules", category == baseline.category());
        view.put("warnings", warnings);
        view.put("model", answer.response().provider() + "/" + answer.response().model() + " (prompt failure-analysis v" + answer.promptVersion() + ")");
        return view;
    }

    private Map<String, Object> evidenceSummary(FailureEvidence e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("errorType", e.errorType());
        m.put("message", firstLines(e.message(), 6));
        m.put("failedStep", e.failedStep());
        m.put("retryAttempt", e.retryAttempt());
        m.put("knownFlaky", e.flaky());
        m.put("environment", e.environment());
        m.put("browser", e.browser());
        m.put("browserDiagnostics", e.browserDiagnostics());
        m.put("apiCalls", e.apiCalls());
        m.put("logLines", e.logTail().size());
        return m;
    }

    private static String normalize(String s) {
        return s.replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT).strip();
    }

    private static String str(Object o, String fallback) {
        return o == null ? fallback : String.valueOf(o);
    }

    private static String firstLines(String text, int n) {
        if (text == null) {
            return null;
        }
        String[] lines = text.split("\\R");
        return lines.length <= n ? text : String.join("\n", java.util.Arrays.copyOf(lines, n));
    }
}
